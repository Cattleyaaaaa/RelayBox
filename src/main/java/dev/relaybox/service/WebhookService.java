package dev.relaybox.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.relaybox.api.ApiModels;
import dev.relaybox.security.WebhookTargetPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class WebhookService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WebhookTargetPolicy targetPolicy;
    private final int maxAttempts;
    private final SecureRandom random = new SecureRandom();

    public WebhookService(JdbcTemplate jdbc, ObjectMapper mapper, WebhookTargetPolicy targetPolicy,
                          @Value("${relaybox.worker.max-attempts:8}") int maxAttempts) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.targetPolicy = targetPolicy;
        this.maxAttempts = maxAttempts;
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
        jdbc.update("INSERT INTO webhook_endpoint (id, tenant_id, url, signing_secret, event_types) VALUES (?, ?, ?, ?, ?::jsonb)",
                id, tenantId, request.url(), secret, writeJson(eventTypes));
        return new ApiModels.EndpointResponse(id, request.url(), eventTypes, secret, true, createdAt, createdAt);
    }

    public List<ApiModels.EndpointResponse> listEndpoints(UUID tenantId) {
        return jdbc.query("SELECT id, url, event_types::text, enabled, created_at, updated_at FROM webhook_endpoint WHERE tenant_id = ? ORDER BY created_at DESC, id DESC",
                (rs, row) -> new ApiModels.EndpointResponse(rs.getObject("id", UUID.class), rs.getString("url"),
                        readStringList(rs.getString("event_types")), null, rs.getBoolean("enabled"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), tenantId);
    }

    @Transactional
    public ApiModels.EndpointStatusResponse setEndpointEnabled(UUID tenantId, UUID endpointId, boolean enabled) {
        List<ApiModels.EndpointStatusResponse> updated = jdbc.query("""
                UPDATE webhook_endpoint SET enabled = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ?
                RETURNING id, enabled, updated_at
                """, (rs, row) -> new ApiModels.EndpointStatusResponse(rs.getObject("id", UUID.class),
                rs.getBoolean("enabled"), rs.getTimestamp("updated_at").toInstant()), enabled, tenantId, endpointId);
        if (updated.isEmpty()) throw new IllegalArgumentException("endpoint does not exist for this tenant");
        return updated.get(0);
    }

    @Transactional
    public ApiModels.PublishEventResponse publish(UUID tenantId, ApiModels.PublishEventRequest request) {
        if (!request.payload().isObject()) throw new IllegalArgumentException("payload must be a JSON object");
        String key = request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                ? UUID.randomUUID().toString() : request.idempotencyKey();
        UUID eventId = UUID.randomUUID();
        String payload = writeJson(request.payload());
        List<UUID> inserted = jdbc.query("""
                INSERT INTO webhook_event (id, tenant_id, event_type, idempotency_key, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                RETURNING id
                """, (rs, row) -> rs.getObject(1, UUID.class), eventId, tenantId, request.type(), key, payload);
        if (inserted.isEmpty()) {
            UUID existingId = jdbc.queryForObject("SELECT id FROM webhook_event WHERE tenant_id = ? AND idempotency_key = ?", UUID.class, tenantId, key);
            Integer count = jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE event_id = ?", Integer.class, existingId);
            return new ApiModels.PublishEventResponse(existingId, count == null ? 0 : count, true);
        }

        List<UUID> deliveries = jdbc.query("""
                SELECT id FROM webhook_endpoint
                WHERE tenant_id = ? AND enabled = TRUE AND event_types ?? ?
                """, (rs, row) -> rs.getObject(1, UUID.class), tenantId, request.type());
        for (UUID endpointId : deliveries) {
            jdbc.update("INSERT INTO webhook_delivery (id, event_id, endpoint_id, max_attempts) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), eventId, endpointId, maxAttempts);
        }
        return new ApiModels.PublishEventResponse(eventId, deliveries.size(), false);
    }

    public ApiModels.PageResponse<ApiModels.DeliveryResponse> listDeliveries(UUID tenantId, UUID eventId,
                                                                              int limit, int offset) {
        validatePage(limit, offset);
        List<ApiModels.DeliveryResponse> rows = jdbc.query("""
                SELECT d.id AS id, d.event_id AS event_id, d.endpoint_id AS endpoint_id,
                       d.status AS status, d.attempt_count AS attempt_count, d.next_attempt_at AS next_attempt_at,
                       d.last_status_code AS last_status_code, d.last_error AS last_error,
                       d.created_at AS created_at, d.updated_at AS updated_at
                FROM webhook_delivery d JOIN webhook_event ev ON ev.id = d.event_id
                WHERE d.event_id = ? AND ev.tenant_id = ? ORDER BY d.created_at, d.id LIMIT ? OFFSET ?
                """, (rs, row) -> new ApiModels.DeliveryResponse(
                rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getObject("endpoint_id", UUID.class), rs.getString("status"), rs.getInt("attempt_count"),
                rs.getTimestamp("next_attempt_at").toInstant(), (Integer) rs.getObject("last_status_code"),
                rs.getString("last_error"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), eventId, tenantId, limit + 1, offset);
        return page(rows, limit, offset);
    }

    public ApiModels.PageResponse<ApiModels.DeliveryAttemptResponse> listAttempts(UUID tenantId, UUID deliveryId,
                                                                                    int limit, int offset) {
        validatePage(limit, offset);
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
        int updated = jdbc.update("""
                UPDATE webhook_delivery d SET status = 'PENDING', max_attempts = d.attempt_count + ?,
                    next_attempt_at = now(), lease_until = NULL, last_status_code = NULL,
                    last_error = NULL, updated_at = now()
                FROM webhook_event ev
                WHERE d.id = ? AND ev.id = d.event_id AND ev.tenant_id = ? AND d.status IN ('DEAD', 'SUCCEEDED')
                """, maxAttempts, deliveryId, tenantId);
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
}
