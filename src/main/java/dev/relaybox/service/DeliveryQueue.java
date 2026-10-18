package dev.relaybox.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class DeliveryQueue {
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final int batchSize;
    private final long retryBaseDelaySeconds;
    private final long retryJitterSeconds;

    public DeliveryQueue(JdbcTemplate jdbc, MeterRegistry meters,
                         @Value("${relaybox.worker.batch-size:20}") int batchSize,
                         @Value("${relaybox.worker.retry-base-delay-seconds:5}") long retryBaseDelaySeconds,
                         @Value("${relaybox.worker.retry-jitter-seconds:3}") long retryJitterSeconds) {
        this.jdbc = jdbc;
        this.meters = meters;
        this.batchSize = batchSize;
        this.retryBaseDelaySeconds = Math.max(1, Math.min(3600, retryBaseDelaySeconds));
        this.retryJitterSeconds = Math.max(0, Math.min(3600, retryJitterSeconds));
    }

    @Transactional
    public List<DeliveryTask> claimBatch() {
        jdbc.update("""
                WITH expired AS (
                    SELECT id, attempt_count, max_attempts FROM webhook_delivery
                    WHERE status = 'IN_FLIGHT' AND lease_until < now()
                    ORDER BY lease_until FOR UPDATE SKIP LOCKED
                ), closed_attempt AS (
                    UPDATE webhook_delivery_attempt a SET status = 'FAILED',
                        error = 'worker lease expired before completion',
                        duration_ms = floor(extract(epoch FROM (now() - a.started_at)) * 1000)::bigint,
                        completed_at = now()
                    FROM expired e
                    WHERE a.delivery_id = e.id AND a.attempt_number = e.attempt_count AND a.status = 'IN_FLIGHT'
                    RETURNING a.delivery_id
                )
                UPDATE webhook_delivery d SET status = CASE WHEN e.attempt_count >= e.max_attempts THEN 'DEAD' ELSE 'PENDING' END,
                    lease_until = NULL, next_attempt_at = now(), updated_at = now(),
                    last_error = CASE WHEN e.attempt_count >= e.max_attempts THEN 'worker lease expired; retry limit reached' ELSE d.last_error END
                FROM expired e, closed_attempt c
                WHERE d.id = e.id AND c.delivery_id = e.id
                """);

        List<DeliveryTask> claimed = jdbc.query("""
                WITH candidates AS (
                    SELECT d.id FROM webhook_delivery d
                    WHERE d.status = 'PENDING' AND d.next_attempt_at <= now()
                    ORDER BY d.next_attempt_at, d.created_at
                    LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE webhook_delivery d SET status = 'IN_FLIGHT', attempt_count = d.attempt_count + 1,
                    lease_until = now() + interval '2 minutes', updated_at = now()
                FROM candidates c, webhook_endpoint e, webhook_event ev
                WHERE d.id = c.id AND e.id = d.endpoint_id AND ev.id = d.event_id
                RETURNING d.id AS delivery_id, d.event_id, d.endpoint_id, d.attempt_count, d.max_attempts,
                    e.url, e.signing_secret, ev.event_type, ev.payload::text AS payload, ev.created_at AS event_created_at
                """, (rs, row) -> new DeliveryTask(
                null, rs.getObject("delivery_id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getObject("endpoint_id", UUID.class), rs.getInt("attempt_count"), rs.getInt("max_attempts"),
                rs.getString("url"), rs.getString("signing_secret"), rs.getString("event_type"),
                rs.getString("payload"), rs.getTimestamp("event_created_at").toInstant()), batchSize);

        List<DeliveryTask> withAttempts = new ArrayList<>(claimed.size());
        for (DeliveryTask task : claimed) {
            UUID attemptId = UUID.randomUUID();
            jdbc.update("INSERT INTO webhook_delivery_attempt (id, delivery_id, attempt_number) VALUES (?, ?, ?)",
                    attemptId, task.deliveryId(), task.attemptCount());
            withAttempts.add(task.withAttemptId(attemptId));
        }
        return withAttempts;
    }

    @Transactional
    public void finish(DeliveryTask task, Integer responseCode, String error) {
        List<Integer> active = jdbc.query("""
                SELECT attempt_count FROM webhook_delivery
                WHERE id = ? AND status = 'IN_FLIGHT' AND attempt_count = ?
                FOR UPDATE
                """, (rs, row) -> rs.getInt(1), task.deliveryId(), task.attemptCount());
        if (active.isEmpty()) return;

        boolean success = error == null;
        boolean exhausted = !success && task.attemptCount() >= task.maxAttempts();
        String attemptStatus = success ? "SUCCEEDED" : "FAILED";
        String deliveryStatus = success ? "SUCCEEDED" : (exhausted ? "DEAD" : "PENDING");
        List<Long> durations = jdbc.query("""
                UPDATE webhook_delivery_attempt SET status = ?, response_code = ?, error = ?,
                    duration_ms = floor(extract(epoch FROM (now() - started_at)) * 1000)::bigint,
                    completed_at = now()
                WHERE id = ? AND status = 'IN_FLIGHT'
                RETURNING duration_ms
                """, (rs, row) -> rs.getLong("duration_ms"), attemptStatus, responseCode,
                truncate(error, 2000), task.attemptId());
        if (durations.isEmpty()) return; // A lease recovery already closed this attempt.

        long exponentialDelay = retryBaseDelaySeconds << Math.min(10, Math.max(0, task.attemptCount() - 1));
        long delaySeconds = Math.min(3600L, exponentialDelay);
        if (retryJitterSeconds > 0) {
            delaySeconds = Math.min(3600L, delaySeconds + ThreadLocalRandom.current().nextLong(0, retryJitterSeconds + 1));
        }
        int deliveryUpdated = jdbc.update("""
                UPDATE webhook_delivery SET status = ?, last_status_code = ?, last_error = ?,
                    next_attempt_at = CASE WHEN ? = 'PENDING' THEN now() + (? * interval '1 second') ELSE next_attempt_at END,
                    lease_until = NULL, updated_at = now()
                WHERE id = ? AND status = 'IN_FLIGHT' AND attempt_count = ?
                """, deliveryStatus, responseCode, truncate(error, 2000), deliveryStatus,
                delaySeconds, task.deliveryId(), task.attemptCount());
        if (deliveryUpdated != 1) throw new IllegalStateException("delivery state changed while completing attempt");
        String outcome = success ? "succeeded" : (exhausted ? "dead" : "retrying");
        meters.counter("relaybox.delivery.attempts", "outcome", outcome).increment();
        Timer.builder("relaybox.delivery.attempt.duration")
                .tag("outcome", outcome).register(meters)
                .record(durations.get(0), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private String truncate(String value, int limit) {
        return value == null ? null : value.substring(0, Math.min(value.length(), limit));
    }

    public record DeliveryTask(UUID attemptId, UUID deliveryId, UUID eventId, UUID endpointId,
                               int attemptCount, int maxAttempts, String url, String signingSecret,
                               String eventType, String payload, java.time.Instant eventCreatedAt) {
        DeliveryTask withAttemptId(UUID id) {
            return new DeliveryTask(id, deliveryId, eventId, endpointId, attemptCount, maxAttempts,
                    url, signingSecret, eventType, payload, eventCreatedAt);
        }
    }
}
