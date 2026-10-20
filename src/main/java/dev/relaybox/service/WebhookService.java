package dev.relaybox.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.relaybox.api.ApiModels;
import dev.relaybox.security.WebhookTargetPolicy;
import dev.relaybox.messaging.DeliveryEventMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.Map;

@Service
public class WebhookService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WebhookTargetPolicy targetPolicy;
    private final int maxAttempts;
    private final int maxPayloadBytes;
    private final int defaultCircuitFailureThreshold;
    private final int defaultCircuitOpenDurationSeconds;
    private final DeliveryServiceClient deliveryService;
    private final boolean deliveryServiceEnabled;
    private final SecureRandom random = new SecureRandom();

    public WebhookService(JdbcTemplate jdbc, ObjectMapper mapper, WebhookTargetPolicy targetPolicy,
                          DeliveryServiceClient deliveryService,
                          @Value("${relaybox.worker.max-attempts:8}") int maxAttempts,
                          @Value("${relaybox.api.max-payload-bytes:262144}") int maxPayloadBytes,
                          @Value("${relaybox.circuit.default-failure-threshold:5}") int defaultCircuitFailureThreshold,
                          @Value("${relaybox.circuit.default-open-duration-seconds:30}") int defaultCircuitOpenDurationSeconds,
                          @Value("${relaybox.delivery-service.enabled:false}") boolean deliveryServiceEnabled) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.targetPolicy = targetPolicy;
        this.deliveryService = deliveryService;
        this.deliveryServiceEnabled = deliveryServiceEnabled;
        this.maxAttempts = maxAttempts;
        this.maxPayloadBytes = Math.max(1024, Math.min(10_000_000, maxPayloadBytes));
        this.defaultCircuitFailureThreshold = Math.max(1, Math.min(1000, defaultCircuitFailureThreshold));
        this.defaultCircuitOpenDurationSeconds = Math.max(1, Math.min(3600, defaultCircuitOpenDurationSeconds));
    }

    @Transactional
    public ApiModels.EndpointResponse createEndpoint(UUID tenantId, ApiModels.CreateEndpointRequest request) {
        targetPolicy.validate(request.url());
        List<String> eventTypes = request.eventTypes().stream().distinct().toList();
        byte[] secretBytes = new byte[32];
        random.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now();
        int circuitFailureThreshold = request.circuitFailureThreshold() == null
                ? defaultCircuitFailureThreshold : request.circuitFailureThreshold();
        int circuitOpenDurationSeconds = request.circuitOpenDurationSeconds() == null
                ? defaultCircuitOpenDurationSeconds : request.circuitOpenDurationSeconds();
        jdbc.update("""
                INSERT INTO webhook_endpoint (id, tenant_id, url, signing_secret, event_types,
                    circuit_failure_threshold, circuit_open_duration_seconds)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                """, id, tenantId, request.url(), secret, writeJson(eventTypes),
                circuitFailureThreshold, circuitOpenDurationSeconds);
        return new ApiModels.EndpointResponse(id, request.url(), eventTypes, secret, true, "CLOSED",
                0, null, circuitFailureThreshold, circuitOpenDurationSeconds, createdAt, createdAt);
    }

    public List<ApiModels.EndpointResponse> listEndpoints(UUID tenantId) {
        List<ApiModels.EndpointResponse> endpoints = jdbc.query("""
                SELECT id, url, event_types::text, enabled, circuit_state, consecutive_failures,
                       circuit_open_until, circuit_failure_threshold, circuit_open_duration_seconds,
                       created_at, updated_at
                FROM webhook_endpoint WHERE tenant_id = ? AND deleted_at IS NULL ORDER BY created_at DESC, id DESC
                """,
                (rs, row) -> new ApiModels.EndpointResponse(rs.getObject("id", UUID.class), rs.getString("url"),
                        readStringList(rs.getString("event_types")), null, rs.getBoolean("enabled"),
                        rs.getString("circuit_state"), rs.getInt("consecutive_failures"),
                        rs.getTimestamp("circuit_open_until") == null ? null : rs.getTimestamp("circuit_open_until").toInstant(),
                        rs.getInt("circuit_failure_threshold"), rs.getInt("circuit_open_duration_seconds"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), tenantId);
        if (!deliveryServiceEnabled || endpoints.isEmpty()) return endpoints;
        Map<UUID, ApiModels.EndpointStatusResponse> statuses = endpointStatusesOrEmpty(tenantId);
        return endpoints.stream().map(endpoint -> {
            ApiModels.EndpointStatusResponse status = statuses.get(endpoint.id());
            if (status == null) return endpoint;
            return new ApiModels.EndpointResponse(endpoint.id(), endpoint.url(), endpoint.eventTypes(),
                    endpoint.signingSecret(), endpoint.enabled(), status.circuitState(), status.consecutiveFailures(),
                    status.circuitOpenUntil(), endpoint.circuitFailureThreshold(), endpoint.circuitOpenDurationSeconds(),
                    endpoint.createdAt(), endpoint.updatedAt());
        }).toList();
    }

    @Transactional
    public ApiModels.EndpointStatusResponse setEndpointEnabled(UUID tenantId, UUID endpointId, boolean enabled) {
        List<ApiModels.EndpointStatusResponse> updated = jdbc.query("""
                UPDATE webhook_endpoint SET enabled = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
                RETURNING id, enabled, circuit_state, consecutive_failures, circuit_open_until, updated_at
                """, (rs, row) -> new ApiModels.EndpointStatusResponse(rs.getObject("id", UUID.class),
                rs.getBoolean("enabled"), rs.getString("circuit_state"), rs.getInt("consecutive_failures"),
                rs.getTimestamp("circuit_open_until") == null ? null : rs.getTimestamp("circuit_open_until").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), enabled, tenantId, endpointId);
        if (updated.isEmpty()) throw new IllegalArgumentException("endpoint does not exist for this tenant");
        ApiModels.EndpointStatusResponse response = updated.get(0);
        if (!deliveryServiceEnabled) return response;
        ApiModels.EndpointStatusResponse status = endpointStatusesOrEmpty(tenantId).get(endpointId);
        if (status == null) return response;
        return new ApiModels.EndpointStatusResponse(endpointId, response.enabled(), status.circuitState(),
                status.consecutiveFailures(), status.circuitOpenUntil(), status.updatedAt());
    }

    @Transactional
    public ApiModels.EndpointResponse updateEndpoint(UUID tenantId, UUID endpointId,
                                                     ApiModels.UpdateEndpointRequest request) {
        if (request.url() != null) targetPolicy.validate(request.url());
        List<String> eventTypes = request.eventTypes() == null
                ? null : request.eventTypes().stream().distinct().toList();
        int updated = jdbc.update("""
                UPDATE webhook_endpoint SET
                    url = COALESCE(CAST(? AS text), url),
                    event_types = COALESCE(CAST(? AS jsonb), event_types),
                    circuit_failure_threshold = COALESCE(CAST(? AS integer), circuit_failure_threshold),
                    circuit_open_duration_seconds = COALESCE(CAST(? AS integer), circuit_open_duration_seconds),
                    updated_at = now()
                WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
                """, request.url(), eventTypes == null ? null : writeJson(eventTypes),
                request.circuitFailureThreshold(), request.circuitOpenDurationSeconds(),
                tenantId, endpointId);
        if (updated == 0) throw new IllegalArgumentException("endpoint does not exist for this tenant");
        return listEndpoints(tenantId).stream().filter(endpoint -> endpoint.id().equals(endpointId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("endpoint does not exist for this tenant"));
    }

    /** Soft delete: accepted deliveries keep their endpoint snapshot and finish normally. */
    @Transactional
    public void deleteEndpoint(UUID tenantId, UUID endpointId) {
        int updated = jdbc.update("""
                UPDATE webhook_endpoint SET deleted_at = now(), enabled = FALSE, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
                """, tenantId, endpointId);
        if (updated == 0) throw new IllegalArgumentException("endpoint does not exist for this tenant");
    }

    /** Returns the new signing secret exactly once; already accepted events keep their snapshot. */
    @Transactional
    public ApiModels.RotateEndpointSecretResponse rotateSigningSecret(UUID tenantId, UUID endpointId) {
        byte[] secretBytes = new byte[32];
        random.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        int updated = jdbc.update("""
                UPDATE webhook_endpoint SET signing_secret = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
                """, secret, tenantId, endpointId);
        if (updated == 0) throw new IllegalArgumentException("endpoint does not exist for this tenant");
        return new ApiModels.RotateEndpointSecretResponse(endpointId, secret);
    }

    @Transactional
    public ApiModels.PublishEventResponse publish(UUID tenantId, ApiModels.PublishEventRequest request) {
        if (!request.payload().isObject()) throw new IllegalArgumentException("payload must be a JSON object");
        String key = request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                ? UUID.randomUUID().toString() : request.idempotencyKey();
        UUID eventId = UUID.randomUUID();
        String payload = writeJson(request.payload());
        if (payload.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
            throw new IllegalArgumentException("payload must not exceed " + maxPayloadBytes + " bytes");
        }
        List<UUID> inserted = jdbc.query("""
                INSERT INTO webhook_event (id, tenant_id, event_type, idempotency_key, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                RETURNING id
                """, (rs, row) -> rs.getObject(1, UUID.class), eventId, tenantId, request.type(), key, payload);
        if (inserted.isEmpty()) {
            List<ExistingEvent> existing = jdbc.query("""
                    SELECT id, event_type, payload = ?::jsonb AS same_payload
                    FROM webhook_event WHERE tenant_id = ? AND idempotency_key = ?
                    """, (rs, row) -> new ExistingEvent(rs.getObject("id", UUID.class),
                    rs.getString("event_type"), rs.getBoolean("same_payload")), payload, tenantId, key);
            if (existing.isEmpty()) throw new IllegalStateException("idempotent event disappeared");
            ExistingEvent original = existing.get(0);
            if (!original.eventType().equals(request.type()) || !original.samePayload()) {
                throw new IdempotencyConflictException("idempotency key is already associated with a different event");
            }
            Integer count;
            if (deliveryServiceEnabled) {
                count = jdbc.query("SELECT jsonb_array_length(message -> 'targets') FROM event_outbox WHERE event_id = ?",
                        rs -> rs.next() ? rs.getInt(1) : 0, original.id());
            } else {
                count = jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE event_id = ?", Integer.class, original.id());
            }
            return new ApiModels.PublishEventResponse(original.id(), count == null ? 0 : count, true);
        }

        if (deliveryServiceEnabled) {
            List<DeliveryEventMessage.Target> targets = jdbc.query("""
                    SELECT id, url, signing_secret, circuit_failure_threshold, circuit_open_duration_seconds
                    FROM webhook_endpoint
                    WHERE tenant_id = ? AND enabled = TRUE AND deleted_at IS NULL AND event_types ?? ?
                    ORDER BY id FOR UPDATE
                    """, (rs, row) -> new DeliveryEventMessage.Target(rs.getObject("id", UUID.class),
                    rs.getString("url"), rs.getString("signing_secret"), maxAttempts,
                    rs.getInt("circuit_failure_threshold"), rs.getInt("circuit_open_duration_seconds")), tenantId, request.type());
            DeliveryEventMessage message = new DeliveryEventMessage(eventId, tenantId, request.type(), payload,
                    Instant.now(), targets);
            jdbc.update("INSERT INTO event_outbox (event_id, tenant_id, message) VALUES (?, ?, ?::jsonb)",
                    eventId, tenantId, writeJson(message));
            return new ApiModels.PublishEventResponse(eventId, targets.size(), false);
        }

        List<UUID> deliveries = jdbc.query("""
                SELECT id FROM webhook_endpoint
                WHERE tenant_id = ? AND enabled = TRUE AND deleted_at IS NULL AND event_types ?? ?
                ORDER BY id FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class), tenantId, request.type());
        for (UUID endpointId : deliveries) {
            long sequence = jdbc.queryForObject("""
                    UPDATE webhook_endpoint SET next_delivery_sequence = next_delivery_sequence + 1
                    WHERE id = ? RETURNING next_delivery_sequence
                    """, Long.class, endpointId);
            jdbc.update("""
                    INSERT INTO webhook_delivery (id, event_id, endpoint_id, sequence_number, max_attempts)
                    VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), eventId, endpointId, sequence, maxAttempts);
        }
        return new ApiModels.PublishEventResponse(eventId, deliveries.size(), false);
    }

    public ApiModels.PageResponse<ApiModels.DeliveryResponse> listDeliveries(UUID tenantId, UUID eventId,
                                                                              int limit, int offset) {
        validatePage(limit, offset);
        if (deliveryServiceEnabled) {
            ApiModels.PageResponse<ApiModels.DeliveryResponse> remote = deliveryService.listDeliveries(tenantId, eventId, limit, offset);
            Integer legacyCount = jdbc.queryForObject("""
                    SELECT count(*) FROM webhook_delivery d JOIN webhook_event e ON e.id = d.event_id
                    WHERE d.event_id = ? AND e.tenant_id = ?
                    """, Integer.class, eventId, tenantId);
            if (!remote.items().isEmpty() || legacyCount == null || legacyCount == 0) return remote;
        }
        List<ApiModels.DeliveryResponse> rows = jdbc.query("""
                SELECT d.id AS id, d.event_id AS event_id, d.endpoint_id AS endpoint_id,
                       d.sequence_number AS sequence_number,
                       d.status AS status, d.attempt_count AS attempt_count, d.max_attempts AS max_attempts,
                       d.next_attempt_at AS next_attempt_at,
                       d.last_status_code AS last_status_code, d.last_error AS last_error,
                       d.created_at AS created_at, d.updated_at AS updated_at
                FROM webhook_delivery d JOIN webhook_event ev ON ev.id = d.event_id
                WHERE d.event_id = ? AND ev.tenant_id = ? ORDER BY d.created_at, d.id LIMIT ? OFFSET ?
                """, (rs, row) -> new ApiModels.DeliveryResponse(
                rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getObject("endpoint_id", UUID.class), rs.getString("status"), rs.getLong("sequence_number"),
                rs.getInt("attempt_count"), rs.getInt("max_attempts"),
                rs.getTimestamp("next_attempt_at").toInstant(), (Integer) rs.getObject("last_status_code"),
                rs.getString("last_error"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), eventId, tenantId, limit + 1, offset);
        return page(rows, limit, offset);
    }

    public ApiModels.PageResponse<ApiModels.DeliveryAttemptResponse> listAttempts(UUID tenantId, UUID deliveryId,
                                                                                    int limit, int offset) {
        validatePage(limit, offset);
        if (deliveryServiceEnabled) {
            ApiModels.PageResponse<ApiModels.DeliveryAttemptResponse> remote =
                    deliveryService.listAttempts(tenantId, deliveryId, limit, offset);
            Integer legacyCount = jdbc.queryForObject("""
                    SELECT count(*) FROM webhook_delivery d JOIN webhook_event e ON e.id = d.event_id
                    WHERE d.id = ? AND e.tenant_id = ?
                    """, Integer.class, deliveryId, tenantId);
            if (!remote.items().isEmpty() || legacyCount == null || legacyCount == 0) return remote;
        }
        List<ApiModels.DeliveryAttemptResponse> rows = jdbc.query("""
                SELECT a.id, a.attempt_number, a.status, a.response_code, a.error,
                       a.duration_ms, a.started_at, a.completed_at
                FROM webhook_delivery_attempt a
                JOIN webhook_delivery d ON d.id = a.delivery_id
                JOIN webhook_event ev ON ev.id = d.event_id
                WHERE d.id = ? AND ev.tenant_id = ?
                ORDER BY a.attempt_number LIMIT ? OFFSET ?
                """, (rs, row) -> new ApiModels.DeliveryAttemptResponse(
                rs.getObject("id", UUID.class), rs.getInt("attempt_number"), rs.getString("status"),
                (Integer) rs.getObject("response_code"), rs.getString("error"),
                (Long) rs.getObject("duration_ms"), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()),
                deliveryId, tenantId, limit + 1, offset);
        return page(rows, limit, offset);
    }

    private void validatePage(int limit, int offset) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        if (offset < 0) throw new IllegalArgumentException("offset must be zero or greater");
    }

    private <T> ApiModels.PageResponse<T> page(List<T> rows, int limit, int offset) {
        boolean hasMore = rows.size() > limit;
        List<T> items = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        return new ApiModels.PageResponse<>(items, limit, offset, hasMore);
    }

    @Transactional
    public void replay(UUID tenantId, UUID deliveryId) {
        if (deliveryServiceEnabled) {
            Integer legacyCount = jdbc.queryForObject("""
                    SELECT count(*) FROM webhook_delivery d JOIN webhook_event e ON e.id = d.event_id
                    WHERE d.id = ? AND e.tenant_id = ? AND d.status IN ('DEAD', 'SUCCEEDED')
                    """, Integer.class, deliveryId, tenantId);
            if (legacyCount == null || legacyCount == 0) {
                deliveryService.replay(tenantId, deliveryId);
                return;
            }
        }
        List<UUID> endpoints = jdbc.query("""
                SELECT endpoint.id
                FROM webhook_delivery delivery
                JOIN webhook_endpoint endpoint ON endpoint.id = delivery.endpoint_id
                JOIN webhook_event event ON event.id = delivery.event_id
                WHERE delivery.id = ? AND event.tenant_id = ?
                  AND delivery.status IN ('DEAD', 'SUCCEEDED')
                FOR UPDATE OF delivery, endpoint
                """, (rs, row) -> rs.getObject(1, UUID.class), deliveryId, tenantId);
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("delivery does not exist or cannot be replayed in its current state");
        }
        long sequence = jdbc.queryForObject("""
                UPDATE webhook_endpoint SET next_delivery_sequence = next_delivery_sequence + 1
                WHERE id = ? RETURNING next_delivery_sequence
                """, Long.class, endpoints.get(0));
        int updated = jdbc.update("""
                UPDATE webhook_delivery d SET status = 'PENDING', sequence_number = ?, max_attempts = d.attempt_count + ?,
                    next_attempt_at = now(), lease_until = NULL, last_status_code = NULL,
                    last_error = NULL, updated_at = now()
                FROM webhook_event ev
                WHERE d.id = ? AND ev.id = d.event_id AND ev.tenant_id = ? AND d.status IN ('DEAD', 'SUCCEEDED')
                """, sequence, maxAttempts, deliveryId, tenantId);
        if (updated == 0) throw new IllegalArgumentException("delivery does not exist or cannot be replayed in its current state");
    }

    public String serialize(JsonNode node) { return writeJson(node); }
    public String serialize(Object value) { return writeJson(value); }

    private String writeJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalArgumentException("payload must be valid JSON", exception); }
    }

    private List<String> readStringList(String json) {
        try {
            List<String> values = mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(ArrayList.class, String.class));
            return List.copyOf(values);
        } catch (JacksonException exception) { throw new IllegalStateException("invalid event type data", exception); }
    }

    private record ExistingEvent(UUID id, String eventType, boolean samePayload) {}

    private Map<UUID, ApiModels.EndpointStatusResponse> endpointStatusesOrEmpty(UUID tenantId) {
        try {
            return deliveryService.endpointStatuses(tenantId);
        } catch (org.springframework.web.client.RestClientException exception) {
            return Map.of();
        }
    }

    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String message) { super(message); }
    }
}
