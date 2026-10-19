package dev.relaybox.delivery.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/tenants/{tenantId}")
public class InternalDeliveryController {
    private final JdbcTemplate jdbc;
    private final int replayMaxAttempts;
    public InternalDeliveryController(JdbcTemplate jdbc,
                                      @Value("${relaybox.worker.max-attempts:8}") int replayMaxAttempts) {
        this.jdbc = jdbc;
        this.replayMaxAttempts = Math.max(1, Math.min(1000, replayMaxAttempts));
    }

    @GetMapping("/events/{eventId}/deliveries")
    public DeliveryModels.PageResponse<DeliveryModels.DeliveryResponse> deliveries(
            @PathVariable UUID tenantId, @PathVariable UUID eventId,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        validatePage(limit, offset);
        List<DeliveryModels.DeliveryResponse> rows = jdbc.query("""
                SELECT d.id, d.event_id, d.endpoint_id, d.status, d.sequence_number, d.attempt_count,
                       d.max_attempts, d.next_attempt_at, d.last_status_code, d.last_error, d.created_at, d.updated_at
                FROM webhook_delivery d JOIN webhook_event e ON e.id = d.event_id
                WHERE d.event_id = ? AND e.tenant_id = ? ORDER BY d.created_at, d.id LIMIT ? OFFSET ?
                """, (rs, row) -> new DeliveryModels.DeliveryResponse(rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class), rs.getObject("endpoint_id", UUID.class), rs.getString("status"),
                rs.getLong("sequence_number"), rs.getInt("attempt_count"), rs.getInt("max_attempts"),
                rs.getTimestamp("next_attempt_at").toInstant(), (Integer) rs.getObject("last_status_code"),
                rs.getString("last_error"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), eventId, tenantId, limit + 1, offset);
        return page(rows, limit, offset);
    }

    @GetMapping("/deliveries/{deliveryId}/attempts")
    public DeliveryModels.PageResponse<DeliveryModels.AttemptResponse> attempts(
            @PathVariable UUID tenantId, @PathVariable UUID deliveryId,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        validatePage(limit, offset);
        List<DeliveryModels.AttemptResponse> rows = jdbc.query("""
                SELECT a.id, a.attempt_number, a.status, a.response_code, a.error, a.duration_ms,
                       a.started_at, a.completed_at
                FROM webhook_delivery_attempt a JOIN webhook_delivery d ON d.id = a.delivery_id
                JOIN webhook_event e ON e.id = d.event_id
                WHERE d.id = ? AND e.tenant_id = ? ORDER BY a.attempt_number LIMIT ? OFFSET ?
                """, (rs, row) -> new DeliveryModels.AttemptResponse(rs.getObject("id", UUID.class),
                rs.getInt("attempt_number"), rs.getString("status"), (Integer) rs.getObject("response_code"),
                rs.getString("error"), (Long) rs.getObject("duration_ms"), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()),
                deliveryId, tenantId, limit + 1, offset);
        return page(rows, limit, offset);
    }

    @GetMapping("/endpoint-status")
    public Map<UUID, DeliveryModels.EndpointStatusResponse> endpointStatuses(@PathVariable UUID tenantId) {
        return jdbc.query("""
                SELECT id, enabled, circuit_state, consecutive_failures, circuit_open_until, updated_at
                FROM webhook_endpoint WHERE tenant_id = ?
                """, rs -> {
            java.util.Map<UUID, DeliveryModels.EndpointStatusResponse> result = new java.util.HashMap<>();
            while (rs.next()) {
                UUID id = rs.getObject("id", UUID.class);
                result.put(id, new DeliveryModels.EndpointStatusResponse(id, rs.getBoolean("enabled"),
                        rs.getString("circuit_state"), rs.getInt("consecutive_failures"),
                        rs.getTimestamp("circuit_open_until") == null ? null : rs.getTimestamp("circuit_open_until").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()));
            }
            return result;
        }, tenantId);
    }

    @PostMapping("/deliveries/{deliveryId}/replay")
    @Transactional
    public void replay(@PathVariable UUID tenantId, @PathVariable UUID deliveryId) {
        List<UUID> endpoints = jdbc.query("""
                SELECT d.endpoint_id FROM webhook_delivery d
                JOIN webhook_event e ON e.id = d.event_id
                WHERE d.id = ? AND e.tenant_id = ? AND d.status IN ('DEAD', 'SUCCEEDED')
                FOR UPDATE OF d
                """, (rs, row) -> rs.getObject(1, UUID.class), deliveryId, tenantId);
        if (endpoints.isEmpty()) throw new IllegalArgumentException("delivery does not exist or cannot be replayed in its current state");
        long sequence = jdbc.queryForObject("""
                UPDATE webhook_endpoint SET next_delivery_sequence = next_delivery_sequence + 1, updated_at = now()
                WHERE id = ? RETURNING next_delivery_sequence
                """, Long.class, endpoints.get(0));
        int updated = jdbc.update("""
                UPDATE webhook_delivery SET status = 'PENDING', sequence_number = ?, max_attempts = attempt_count + ?,
                    next_attempt_at = now(), lease_until = NULL, last_status_code = NULL,
                    last_error = NULL, updated_at = now()
                WHERE id = ? AND status IN ('DEAD', 'SUCCEEDED')
                """, sequence, replayMaxAttempts, deliveryId);
        if (updated == 0) throw new IllegalArgumentException("delivery state changed before replay");
    }

    private void validatePage(int limit, int offset) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        if (offset < 0) throw new IllegalArgumentException("offset must be zero or greater");
    }

    private <T> DeliveryModels.PageResponse<T> page(List<T> rows, int limit, int offset) {
        boolean hasMore = rows.size() > limit;
        return new DeliveryModels.PageResponse<>(hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows),
                limit, offset, hasMore);
    }
}
