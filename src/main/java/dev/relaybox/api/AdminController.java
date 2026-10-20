package dev.relaybox.api;

import dev.relaybox.service.DeliveryServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.time.Instant;

/**
 * Master-key protected operational endpoints. They make quarantined outbox rows and
 * broker dead letters inspectable and replayable without direct database or broker access.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final JdbcTemplate jdbc;
    private final DeliveryServiceClient deliveryService;
    private final boolean deliveryServiceEnabled;

    public AdminController(JdbcTemplate jdbc, DeliveryServiceClient deliveryService,
                           @Value("${relaybox.delivery-service.enabled:false}") boolean deliveryServiceEnabled) {
        this.jdbc = jdbc;
        this.deliveryService = deliveryService;
        this.deliveryServiceEnabled = deliveryServiceEnabled;
    }

    @GetMapping("/outbox")
    public ApiModels.PageResponse<ApiModels.OutboxResponse> outbox(
            @RequestParam(defaultValue = "abandoned") String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        validatePage(limit, offset);
        String filter = switch (status) {
            case "pending" -> "published_at IS NULL AND abandoned_at IS NULL";
            case "abandoned" -> "published_at IS NULL AND abandoned_at IS NOT NULL";
            case "published" -> "published_at IS NOT NULL";
            default -> throw new IllegalArgumentException("status must be pending, abandoned, or published");
        };
        List<ApiModels.OutboxResponse> rows = jdbc.query("""
                SELECT event_id, tenant_id, attempts, failure_kind, last_error, next_attempt_at,
                       abandoned_at, published_at, created_at
                FROM event_outbox
                WHERE %s
                ORDER BY created_at DESC, event_id DESC
                LIMIT ? OFFSET ?
                """.formatted(filter), (rs, row) -> new ApiModels.OutboxResponse(
                rs.getObject("event_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getInt("attempts"), rs.getString("failure_kind"), rs.getString("last_error"),
                rs.getTimestamp("next_attempt_at").toInstant(),
                timestamp(rs.getTimestamp("abandoned_at")), timestamp(rs.getTimestamp("published_at")),
                rs.getTimestamp("created_at").toInstant()), limit + 1, offset);
        boolean hasMore = rows.size() > limit;
        return new ApiModels.PageResponse<>(hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows),
                limit, offset, hasMore);
    }

    @PostMapping("/outbox/{eventId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void retryOutbox(@PathVariable UUID eventId) {
        int updated = jdbc.update("""
                UPDATE event_outbox
                SET abandoned_at = NULL, attempts = 0, failure_kind = NULL, last_error = NULL,
                    next_attempt_at = now(), updated_at = now()
                WHERE event_id = ? AND published_at IS NULL
                """, eventId);
        if (updated == 0) throw new IllegalArgumentException("outbox event does not exist or was already published");
    }

    @PostMapping("/outbox/{eventId}/abandon")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void abandonOutbox(@PathVariable UUID eventId) {
        int updated = jdbc.update("""
                UPDATE event_outbox SET abandoned_at = now(), updated_at = now()
                WHERE event_id = ? AND published_at IS NULL AND abandoned_at IS NULL
                """, eventId);
        if (updated == 0) throw new IllegalArgumentException("outbox event does not exist or is not publishable");
    }

    @GetMapping("/dead-letters")
    public ApiModels.PageResponse<ApiModels.DeadLetterResponse> deadLetters(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        validatePage(limit, offset);
        if (!deliveryServiceEnabled) throw serviceUnavailable();
        return deliveryService.listDeadLetters(limit, offset);
    }

    @PostMapping("/dead-letters/{id}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void replayDeadLetter(@PathVariable UUID id) {
        if (!deliveryServiceEnabled) throw serviceUnavailable();
        deliveryService.replayDeadLetter(id);
    }

    private ResponseStatusException serviceUnavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "dead letters are owned by the delivery service, which is disabled in this deployment");
    }

    private void validatePage(int limit, int offset) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        if (offset < 0) throw new IllegalArgumentException("offset must be zero or greater");
    }

    private Instant timestamp(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
