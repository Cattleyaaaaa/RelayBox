package dev.relaybox.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import dev.relaybox.service.DeliveryServiceClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Local-only webhook receiver used to demonstrate retries and manual replay. */
@RestController
@RequestMapping("/dev/receiver")
@ConditionalOnProperty(name = "relaybox.test-receiver.enabled", havingValue = "true")
public class DevelopmentReceiverController {
    private static final int MAX_RECORDED_REQUESTS = 100;
    private final byte[] masterKey;
    private final JdbcTemplate jdbc;
    private final DeliveryServiceClient deliveryService;
    private final boolean deliveryServiceEnabled;
    private final AtomicInteger failuresRemaining = new AtomicInteger();
    private final AtomicInteger failureStatus = new AtomicInteger(500);
    private final AtomicLong totalRequests = new AtomicLong();
    private final Deque<ReceivedRequest> recentRequests = new ArrayDeque<>();
    private final AtomicInteger chaosRequests = new AtomicInteger();
    private final Deque<ChaosReceipt> chaosReceipts = new ArrayDeque<>();
    private final AtomicReference<UUID> chaosRunId = new AtomicReference<>(UUID.randomUUID());
    private final int chaosFirstDelayMillis;

    public DevelopmentReceiverController(@Value("${relaybox.master-key}") String masterKey,
                                         @Value("${relaybox.test-receiver.chaos-first-delay-ms:30000}") int chaosFirstDelayMillis,
                                         JdbcTemplate jdbc,
                                         DeliveryServiceClient deliveryService,
                                         @Value("${relaybox.delivery-service.enabled:false}") boolean deliveryServiceEnabled) {
        this.masterKey = masterKey.getBytes(StandardCharsets.UTF_8);
        this.jdbc = jdbc;
        this.deliveryService = deliveryService;
        this.deliveryServiceEnabled = deliveryServiceEnabled;
        this.chaosFirstDelayMillis = Math.max(0, Math.min(60_000, chaosFirstDelayMillis));
    }

    @PostMapping("/deliver")
    public ResponseEntity<Void> receive(HttpServletRequest request, @RequestBody String body) {
        long requestNumber = totalRequests.incrementAndGet();
        ReceivedRequest received = new ReceivedRequest(
                requestNumber,
                Instant.now(),
                request.getHeader("X-Webhook-Id"),
                request.getHeader("X-Webhook-Delivery"),
                request.getHeader("X-Webhook-Timestamp"),
                request.getHeader("X-Webhook-Signature"),
                body);
        synchronized (recentRequests) {
            recentRequests.addFirst(received);
            while (recentRequests.size() > MAX_RECORDED_REQUESTS) recentRequests.removeLast();
        }

        int remaining = failuresRemaining.getAndUpdate(value -> Math.max(0, value - 1));
        return ResponseEntity.status(remaining > 0 ? failureStatus.get() : HttpStatus.OK.value()).build();
    }

    /** The first request remains open so a demo worker can be killed after it sends the request. */
    @PostMapping("/chaos/deliver")
    public ResponseEntity<Void> receiveChaos(HttpServletRequest request, @RequestBody String body) {
        String deliveryId = request.getHeader("X-Webhook-Delivery");
        String eventId = request.getHeader("X-Webhook-Id");
        if (deliveryId == null || eventId == null) return ResponseEntity.badRequest().build();
        UUID deliveryUuid;
        UUID eventUuid;
        try {
            deliveryUuid = UUID.fromString(deliveryId);
            eventUuid = UUID.fromString(eventId);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().build();
        }
        int number;
        synchronized (chaosReceipts) {
            number = chaosRequests.incrementAndGet();
            UUID runId = chaosRunId.get();
            boolean applied = jdbc.update("""
                    INSERT INTO dev_receiver_effect (delivery_id, run_id, event_id, request_body)
                    VALUES (?, ?, ?, CAST(? AS jsonb))
                    ON CONFLICT (delivery_id) DO NOTHING
                    """, deliveryUuid, runId, eventUuid, body) == 1;
            if (!applied) {
                Boolean sameRequest = jdbc.queryForObject("""
                        SELECT event_id = ? AND request_body = CAST(? AS jsonb)
                        FROM dev_receiver_effect WHERE delivery_id = ?
                        """, Boolean.class, eventUuid, body, deliveryUuid);
                if (!Boolean.TRUE.equals(sameRequest)) {
                    chaosReceipts.addLast(new ChaosReceipt(number, Instant.now(),
                            deliveryId, eventId, false, true, HttpStatus.CONFLICT.value()));
                    return ResponseEntity.status(HttpStatus.CONFLICT).build();
                }
            }
            chaosReceipts.addLast(new ChaosReceipt(number, Instant.now(),
                    deliveryId, eventId, applied, false, HttpStatus.OK.value()));
        }
        if (number == 1) {
            try {
                Thread.sleep(chaosFirstDelayMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/chaos/arm")
    public ChaosState armChaos(@RequestHeader(name = "X-API-Key", required = false) String providedKey) {
        requireMasterKey(providedKey);
        synchronized (chaosReceipts) {
            chaosRequests.set(0);
            chaosReceipts.clear();
            chaosRunId.set(UUID.randomUUID());
        }
        return chaosState();
    }

    @GetMapping("/chaos/state")
    public ChaosState getChaosState(@RequestHeader(name = "X-API-Key", required = false) String providedKey) {
        requireMasterKey(providedKey);
        return chaosState();
    }

    @GetMapping("/chaos/report")
    public ChaosReport getChaosReport(@RequestHeader(name = "X-API-Key", required = false) String providedKey) {
        requireMasterKey(providedKey);
        ChaosState receiver = chaosState();
        if (receiver.receipts().isEmpty()) return new ChaosReport(receiver, null, null, List.of());
        ChaosReceipt latest = receiver.receipts().get(receiver.receipts().size() - 1);
        UUID deliveryId;
        UUID eventId;
        try {
            deliveryId = UUID.fromString(latest.deliveryId());
            eventId = UUID.fromString(latest.eventId());
        } catch (IllegalArgumentException exception) {
            return new ChaosReport(receiver, latest.deliveryId(), null, List.of());
        }
        if (deliveryServiceEnabled) {
            List<UUID> tenantIds = jdbc.query("SELECT tenant_id FROM webhook_event WHERE id = ?",
                    (rs, row) -> rs.getObject(1, UUID.class), eventId);
            if (tenantIds.isEmpty()) return new ChaosReport(receiver, latest.deliveryId(), null, List.of());
            var deliveries = deliveryService.listDeliveries(tenantIds.get(0), eventId, 100, 0).items();
            var delivery = deliveries.stream().filter(item -> item.id().equals(deliveryId)).findFirst().orElse(null);
            if (delivery == null) return new ChaosReport(receiver, latest.deliveryId(), null, List.of());
            List<ChaosAttempt> attempts = deliveryService.listAttempts(tenantIds.get(0), deliveryId, 100, 0)
                    .items().stream().map(attempt -> new ChaosAttempt(attempt.attemptNumber(), attempt.status(),
                            attempt.responseCode(), attempt.error(), attempt.startedAt(), attempt.completedAt())).toList();
            return new ChaosReport(receiver, latest.deliveryId(), delivery.status(), attempts);
        }
        List<ChaosAttempt> attempts = jdbc.query("""
                SELECT a.attempt_number, a.status AS attempt_status,
                       a.response_code, a.error, a.started_at, a.completed_at
                FROM webhook_delivery d
                JOIN webhook_delivery_attempt a ON a.delivery_id = d.id
                WHERE d.id = ? AND d.event_id = ?
                ORDER BY a.attempt_number
                """, (rs, row) -> new ChaosAttempt(rs.getInt("attempt_number"),
                rs.getString("attempt_status"), (Integer) rs.getObject("response_code"),
                rs.getString("error"), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null :
                        rs.getTimestamp("completed_at").toInstant()), deliveryId, eventId);
        List<String> statuses = jdbc.query("SELECT status FROM webhook_delivery WHERE id = ? AND event_id = ?",
                (rs, row) -> rs.getString(1), deliveryId, eventId);
        return new ChaosReport(receiver, latest.deliveryId(),
                statuses.isEmpty() ? null : statuses.get(0), attempts);
    }

    private ChaosState chaosState() {
        synchronized (chaosReceipts) {
            UUID runId = chaosRunId.get();
            Integer effects = jdbc.queryForObject(
                    "SELECT count(*) FROM dev_receiver_effect WHERE run_id = ?", Integer.class, runId);
            return new ChaosState(chaosRequests.get(), effects == null ? 0 : effects,
                    new ArrayList<>(chaosReceipts));
        }
    }

    @PostMapping("/config")
    public ReceiverState configure(@RequestHeader(name = "X-API-Key", required = false) String providedKey,
                                   @Valid @RequestBody ReceiverConfig config) {
        requireMasterKey(providedKey);
        failuresRemaining.set(config.failuresBeforeSuccess());
        failureStatus.set(config.failureStatus());
        return state();
    }

    @PostMapping("/reset")
    public ReceiverState reset(@RequestHeader(name = "X-API-Key", required = false) String providedKey) {
        requireMasterKey(providedKey);
        failuresRemaining.set(0);
        failureStatus.set(HttpStatus.INTERNAL_SERVER_ERROR.value());
        totalRequests.set(0);
        synchronized (recentRequests) {
            recentRequests.clear();
        }
        return state();
    }

    @GetMapping("/state")
    public ReceiverState getState(@RequestHeader(name = "X-API-Key", required = false) String providedKey) {
        requireMasterKey(providedKey);
        return state();
    }

    private ReceiverState state() {
        List<ReceivedRequest> requests;
        synchronized (recentRequests) {
            requests = new ArrayList<>(recentRequests);
        }
        return new ReceiverState(totalRequests.get(), failuresRemaining.get(), failureStatus.get(), requests);
    }

    private void requireMasterKey(String providedKey) {
        if (providedKey == null || !MessageDigest.isEqual(masterKey, providedKey.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Master API key required");
        }
    }

    public record ReceiverConfig(@Min(0) @Max(1000) int failuresBeforeSuccess,
                                 @Min(400) @Max(599) int failureStatus) {}

    public record ReceiverState(long totalRequests, int failuresRemaining, int failureStatus,
                                List<ReceivedRequest> recentRequests) {}

    public record ReceivedRequest(long requestNumber, Instant receivedAt, String eventId,
                                  String deliveryId, String timestamp, String signature, String body) {}
    public record ChaosState(int receivedCount, int appliedBusinessEffects, List<ChaosReceipt> receipts) {}
    public record ChaosReceipt(int requestNumber, Instant receivedAt, String deliveryId, String eventId,
                               boolean businessEffectApplied, boolean idempotencyConflict, int responseCode) {}
    public record ChaosReport(ChaosState receiver, String deliveryId, String deliveryStatus,
                              List<ChaosAttempt> attempts) {}
    public record ChaosAttempt(int attemptNumber, String status, Integer responseCode, String error,
                               Instant startedAt, Instant completedAt) {}
}
