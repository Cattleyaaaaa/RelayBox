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
    private final int leaseSeconds;

    public DeliveryQueue(JdbcTemplate jdbc, MeterRegistry meters,
                         @Value("${relaybox.worker.batch-size:20}") int batchSize,
                         @Value("${relaybox.worker.retry-base-delay-seconds:5}") long retryBaseDelaySeconds,
                         @Value("${relaybox.worker.retry-jitter-seconds:3}") long retryJitterSeconds,
                         @Value("${relaybox.worker.lease-seconds:120}") int leaseSeconds) {
        this.jdbc = jdbc;
        this.meters = meters;
        this.batchSize = batchSize;
        this.retryBaseDelaySeconds = Math.max(1, Math.min(3600, retryBaseDelaySeconds));
        this.retryJitterSeconds = Math.max(0, Math.min(3600, retryJitterSeconds));
        this.leaseSeconds = Math.max(12, Math.min(3600, leaseSeconds));
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

        // A probe worker that crashed must release its endpoint reservation.
        jdbc.update("""
                UPDATE webhook_endpoint
                SET circuit_probe_lease_until = NULL, updated_at = now()
                WHERE circuit_state = 'HALF_OPEN' AND circuit_probe_lease_until < now()
                """);
        jdbc.update("""
                UPDATE webhook_endpoint
                SET circuit_state = 'HALF_OPEN', circuit_probe_lease_until = NULL, updated_at = now()
                WHERE circuit_state = 'OPEN' AND circuit_open_until <= now()
                """);

        List<DeliveryTask> claimed = jdbc.query("""
                WITH candidates AS (
                    SELECT d.id FROM webhook_delivery d
                    JOIN webhook_endpoint endpoint ON endpoint.id = d.endpoint_id
                    WHERE d.status = 'PENDING' AND d.next_attempt_at <= now()
                      AND endpoint.enabled = TRUE AND endpoint.circuit_state = 'CLOSED'
                      AND NOT EXISTS (
                          SELECT 1 FROM webhook_delivery earlier
                          WHERE earlier.endpoint_id = d.endpoint_id
                            AND earlier.sequence_number < d.sequence_number
                            AND earlier.status IN ('PENDING', 'IN_FLIGHT')
                      )
                    ORDER BY d.next_attempt_at, d.created_at, d.id
                    LIMIT ? FOR UPDATE OF d SKIP LOCKED
                )
                UPDATE webhook_delivery d SET status = 'IN_FLIGHT', attempt_count = d.attempt_count + 1,
                    lease_until = now() + (? * interval '1 second'), updated_at = now()
                FROM candidates c, webhook_endpoint e, webhook_event ev
                WHERE d.id = c.id AND e.id = d.endpoint_id AND ev.id = d.event_id
                RETURNING d.id AS delivery_id, d.event_id, d.endpoint_id, d.attempt_count, d.max_attempts,
                    e.url, e.signing_secret, ev.event_type, ev.payload::text AS payload, ev.created_at AS event_created_at
                """, (rs, row) -> new DeliveryTask(
                null, rs.getObject("delivery_id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getObject("endpoint_id", UUID.class), rs.getInt("attempt_count"), rs.getInt("max_attempts"),
                rs.getString("url"), rs.getString("signing_secret"), rs.getString("event_type"),
                rs.getString("payload"), rs.getTimestamp("event_created_at").toInstant()), batchSize, leaseSeconds);

        if (claimed.size() < batchSize) {
            claimed.addAll(claimHalfOpenBatch(batchSize - claimed.size()));
        }

        List<DeliveryTask> withAttempts = new ArrayList<>(claimed.size());
        for (DeliveryTask task : claimed) {
            UUID attemptId = UUID.randomUUID();
            jdbc.update("INSERT INTO webhook_delivery_attempt (id, delivery_id, attempt_number) VALUES (?, ?, ?)",
                    attemptId, task.deliveryId(), task.attemptCount());
            withAttempts.add(task.withAttemptId(attemptId));
        }
        return withAttempts;
    }

    private List<DeliveryTask> claimHalfOpenBatch(int limit) {
        return jdbc.query("""
                WITH eligible_endpoints AS MATERIALIZED (
                    SELECT id FROM webhook_endpoint
                    WHERE enabled = TRUE AND circuit_state = 'HALF_OPEN'
                      AND (circuit_probe_lease_until IS NULL OR circuit_probe_lease_until < now())
                    ORDER BY circuit_open_until NULLS FIRST, id
                    LIMIT ? FOR UPDATE SKIP LOCKED
                ), candidates AS MATERIALIZED (
                    SELECT DISTINCT ON (d.endpoint_id) d.id AS delivery_id, d.endpoint_id
                    FROM webhook_delivery d
                    JOIN eligible_endpoints endpoint ON endpoint.id = d.endpoint_id
                    WHERE d.status = 'PENDING' AND d.next_attempt_at <= now()
                      AND NOT EXISTS (
                          SELECT 1 FROM webhook_delivery earlier
                          WHERE earlier.endpoint_id = d.endpoint_id
                            AND earlier.sequence_number < d.sequence_number
                            AND earlier.status IN ('PENDING', 'IN_FLIGHT')
                      )
                    ORDER BY d.endpoint_id, d.sequence_number, d.next_attempt_at, d.id
                ), reserved AS (
                    UPDATE webhook_endpoint endpoint
                    SET circuit_probe_lease_until = now() + (? * interval '1 second'), updated_at = now()
                    FROM candidates candidate
                    WHERE endpoint.id = candidate.endpoint_id
                    RETURNING candidate.delivery_id, endpoint.id AS endpoint_id
                ), claimed AS (
                    UPDATE webhook_delivery d SET status = 'IN_FLIGHT', attempt_count = d.attempt_count + 1,
                        lease_until = now() + (? * interval '1 second'), updated_at = now()
                    FROM reserved r
                    WHERE d.id = r.delivery_id
                    RETURNING d.id AS delivery_id, d.event_id, d.endpoint_id, d.attempt_count, d.max_attempts
                )
                SELECT c.delivery_id, c.event_id, c.endpoint_id, c.attempt_count, c.max_attempts,
                       endpoint.url, endpoint.signing_secret, ev.event_type,
                       ev.payload::text AS payload, ev.created_at AS event_created_at
                FROM claimed c
                JOIN webhook_endpoint endpoint ON endpoint.id = c.endpoint_id
                JOIN webhook_event ev ON ev.id = c.event_id
                """, (rs, row) -> new DeliveryTask(null, rs.getObject("delivery_id", UUID.class),
                rs.getObject("event_id", UUID.class), rs.getObject("endpoint_id", UUID.class),
                rs.getInt("attempt_count"), rs.getInt("max_attempts"), rs.getString("url"),
                rs.getString("signing_secret"), rs.getString("event_type"), rs.getString("payload"),
                rs.getTimestamp("event_created_at").toInstant()), limit, leaseSeconds, leaseSeconds);
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
        boolean exhausted = !success &&
                (task.attemptCount() >= task.maxAttempts() || !isRetryableFailure(responseCode));
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
        // A permanent HTTP response proves the receiver is reachable; it should not trip
        // an availability circuit even though this particular delivery is rejected.
        if (success || !isRetryableFailure(responseCode)) {
            jdbc.update("""
                    UPDATE webhook_endpoint SET circuit_state = 'CLOSED', consecutive_failures = 0,
                        circuit_open_until = NULL, circuit_probe_lease_until = NULL, updated_at = now()
                    WHERE id = ?
                    """, task.endpointId());
        } else {
            jdbc.update("""
                    UPDATE webhook_endpoint SET
                        circuit_state = CASE
                            WHEN circuit_state = 'HALF_OPEN' THEN 'OPEN'
                            WHEN circuit_state = 'CLOSED' AND consecutive_failures + 1 >= circuit_failure_threshold THEN 'OPEN'
                            ELSE circuit_state END,
                        circuit_open_until = CASE
                            WHEN circuit_state = 'HALF_OPEN' OR
                                 (circuit_state = 'CLOSED' AND consecutive_failures + 1 >= circuit_failure_threshold)
                            THEN now() + (circuit_open_duration_seconds * interval '1 second')
                            ELSE circuit_open_until END,
                        consecutive_failures = CASE WHEN circuit_state = 'OPEN' THEN consecutive_failures
                            ELSE LEAST(consecutive_failures + 1, circuit_failure_threshold) END,
                        circuit_probe_lease_until = CASE WHEN circuit_state = 'HALF_OPEN' OR
                            (circuit_state = 'CLOSED' AND consecutive_failures + 1 >= circuit_failure_threshold)
                            THEN NULL ELSE circuit_probe_lease_until END,
                        updated_at = now()
                    WHERE id = ?
                    """, task.endpointId());
        }
        String outcome = success ? "succeeded" : (exhausted ? "dead" : "retrying");
        meters.counter("relaybox.delivery.attempts", "outcome", outcome).increment();
        Timer.builder("relaybox.delivery.attempt.duration")
                .tag("outcome", outcome).register(meters)
                .record(durations.get(0), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private String truncate(String value, int limit) {
        return value == null ? null : value.substring(0, Math.min(value.length(), limit));
    }

    private boolean isRetryableFailure(Integer responseCode) {
        return responseCode == null || responseCode == 408 || responseCode == 425 ||
                responseCode == 429 || responseCode >= 500;
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
