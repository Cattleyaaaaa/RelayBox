package dev.relaybox.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "relaybox.outbox", name = "enabled", havingValue = "true")
public class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final int batchSize;
    private final int maxAttempts;
    private final long retryBaseDelayMs;
    private final long retryMaxDelayMs;
    private final Timer batchTimer;
    private final Timer confirmTimer;
    private final DistributionSummary batchSizes;
    private final Counter published;
    private final Counter failures;
    private final Counter quarantined;

    public OutboxDispatcher(JdbcTemplate jdbc, RabbitTemplate rabbit, MeterRegistry meters,
                            @Value("${relaybox.outbox.batch-size:40}") int configuredBatchSize,
                            @Value("${relaybox.outbox.max-attempts:50}") int configuredMaxAttempts,
                            @Value("${relaybox.outbox.retry-base-delay-ms:200}") long configuredRetryBaseDelayMs,
                            @Value("${relaybox.outbox.retry-max-delay-ms:60000}") long configuredRetryMaxDelayMs) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.batchSize = Math.max(1, Math.min(200, configuredBatchSize));
        this.maxAttempts = Math.max(1, Math.min(1000, configuredMaxAttempts));
        this.retryBaseDelayMs = Math.max(50, Math.min(60_000, configuredRetryBaseDelayMs));
        this.retryMaxDelayMs = Math.max(this.retryBaseDelayMs, Math.min(300_000, configuredRetryMaxDelayMs));
        this.batchTimer = meters.timer("relaybox.outbox.batch.duration");
        this.confirmTimer = meters.timer("relaybox.outbox.confirm.duration");
        this.batchSizes = DistributionSummary.builder("relaybox.outbox.batch.size").register(meters);
        this.published = meters.counter("relaybox.outbox.messages.published");
        this.failures = meters.counter("relaybox.outbox.messages.failed");
        this.quarantined = meters.counter("relaybox.outbox.messages.quarantined");
        Gauge.builder("relaybox.outbox.unpublished", jdbc, template -> count(template,
                "SELECT count(*) FROM event_outbox WHERE published_at IS NULL AND abandoned_at IS NULL"))
                .register(meters);
        Gauge.builder("relaybox.outbox.quarantined", jdbc, template -> count(template,
                "SELECT count(*) FROM event_outbox WHERE abandoned_at IS NOT NULL"))
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${relaybox.outbox.poll-interval-ms:100}")
    @Transactional
    public void publishPending() {
        long started = System.nanoTime();
        try {
            Boolean acquired = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(714920631)", Boolean.class);
            if (!Boolean.TRUE.equals(acquired)) return;
            List<OutboxRow> rows = claimBatch();
            if (!rows.isEmpty()) batchSizes.record(rows.size());
            for (OutboxRow row : rows) {
                // A retry is left at the head of the queue so dispatch order survives a
                // transient broker failure. A quarantined message is skipped after the
                // operator has been given a durable record to inspect and replay.
                if (publish(row) == PublishOutcome.RETRY) break;
            }
        } finally {
            batchTimer.record(Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private List<OutboxRow> claimBatch() {
        return jdbc.query("""
                SELECT event_id, message::text AS message, attempts
                FROM event_outbox
                WHERE published_at IS NULL AND abandoned_at IS NULL
                  AND next_attempt_at <= now()
                  AND dispatch_order < COALESCE((
                      SELECT min(dispatch_order) FROM event_outbox
                      WHERE published_at IS NULL AND abandoned_at IS NULL AND next_attempt_at > now()
                  ), 9223372036854775807)
                ORDER BY dispatch_order
                LIMIT ? FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new OutboxRow(rs.getObject("event_id", UUID.class),
                rs.getString("message"), rs.getInt("attempts")), batchSize);
    }

    private PublishOutcome publish(OutboxRow row) {
        try {
            jdbc.update("""
                    UPDATE event_outbox SET attempts = attempts + 1, updated_at = now()
                    WHERE event_id = ? AND published_at IS NULL AND abandoned_at IS NULL
                    """, row.eventId());
            CorrelationData correlation = new CorrelationData(row.eventId().toString());
            long confirmStarted = System.nanoTime();
            rabbit.convertAndSend(RabbitTopology.EXCHANGE, RabbitTopology.EVENT_ACCEPTED, row.message(), message -> {
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            }, correlation);
            CorrelationData.Confirm confirm;
            try {
                confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            } finally {
                confirmTimer.record(Duration.ofNanos(System.nanoTime() - confirmStarted));
            }
            if (!confirm.isAck()) throw new IllegalStateException("RabbitMQ did not confirm event: " + confirm.getReason());
            if (correlation.getReturned() != null) throw new IllegalStateException("RabbitMQ did not route event to a queue");
            int marked = jdbc.update("""
                    UPDATE event_outbox
                    SET published_at = now(), last_error = NULL, failure_kind = NULL, updated_at = now()
                    WHERE event_id = ? AND published_at IS NULL AND abandoned_at IS NULL
                    """, row.eventId());
            if (marked == 1) published.increment();
            return PublishOutcome.PUBLISHED;
        } catch (Exception exception) {
            return recordFailure(row, exception);
        }
    }

    private PublishOutcome recordFailure(OutboxRow row, Exception exception) {
        failures.increment();
        int attempt = row.attempts() + 1;
        String message = safeMessage(exception);
        FailureKind kind = classify(exception);
        if (kind == FailureKind.MESSAGE && attempt >= maxAttempts) {
            int quarantinedRows = jdbc.update("""
                    UPDATE event_outbox
                    SET abandoned_at = now(), failure_kind = ?, last_error = ?, updated_at = now()
                    WHERE event_id = ? AND published_at IS NULL AND abandoned_at IS NULL
                    """, kind.name(), message, row.eventId());
            if (quarantinedRows == 1) quarantined.increment();
            log.warn("Quarantined outbox event {} after {} attempts: {}", row.eventId(), attempt, message);
            return PublishOutcome.ABANDONED;
        }
        jdbc.update("""
                UPDATE event_outbox
                SET failure_kind = ?, last_error = ?,
                    next_attempt_at = now() + (? * interval '1 millisecond'), updated_at = now()
                WHERE event_id = ? AND published_at IS NULL AND abandoned_at IS NULL
                """, kind.name(), message, retryDelayMs(attempt), row.eventId());
        return PublishOutcome.RETRY;
    }

    private long retryDelayMs(int attempt) {
        return Math.min(retryMaxDelayMs, retryBaseDelayMs << Math.min(10, Math.max(0, attempt - 1)));
    }

    /**
     * Only deterministic payload/conversion failures are quarantined. Broker outages,
     * NACKs and unroutable messages are treated as infrastructure faults: quarantining
     * them would silently drop every event accepted during an outage.
     */
    private FailureKind classify(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof MessageConversionException) return FailureKind.MESSAGE;
        }
        return FailureKind.INFRASTRUCTURE;
    }

    private long count(JdbcTemplate template, String sql) {
        Long value = template.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private String safeMessage(Exception exception) {
        String value = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    enum PublishOutcome { PUBLISHED, RETRY, ABANDONED }

    enum FailureKind { MESSAGE, INFRASTRUCTURE }

    record OutboxRow(UUID eventId, String message, int attempts) {}
}
