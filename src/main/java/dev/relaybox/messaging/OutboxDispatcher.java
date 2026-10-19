package dev.relaybox.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final int batchSize;
    private final Timer batchTimer;
    private final Timer confirmTimer;
    private final DistributionSummary batchSizes;
    private final Counter published;
    private final Counter failures;

    public OutboxDispatcher(JdbcTemplate jdbc, RabbitTemplate rabbit, MeterRegistry meters,
                            @Value("${relaybox.outbox.batch-size:40}") int configuredBatchSize) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.batchSize = Math.max(1, Math.min(200, configuredBatchSize));
        this.batchTimer = meters.timer("relaybox.outbox.batch.duration");
        this.confirmTimer = meters.timer("relaybox.outbox.confirm.duration");
        this.batchSizes = DistributionSummary.builder("relaybox.outbox.batch.size").register(meters);
        this.published = meters.counter("relaybox.outbox.messages.published");
        this.failures = meters.counter("relaybox.outbox.messages.failed");
        Gauge.builder("relaybox.outbox.unpublished", jdbc, template -> {
            Long count = template.queryForObject(
                    "SELECT count(*) FROM event_outbox WHERE published_at IS NULL", Long.class);
            return count == null ? 0 : count;
        }).register(meters);
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
                if (!publish(row)) break;
            }
        } finally {
            batchTimer.record(Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private List<OutboxRow> claimBatch() {
        List<OutboxRow> rows = jdbc.query("""
                SELECT event_id, message::text AS message
                FROM event_outbox
                WHERE published_at IS NULL
                ORDER BY dispatch_order
                LIMIT ? FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new OutboxRow(rs.getObject("event_id", UUID.class), rs.getString("message")),
                batchSize);
        return rows;
    }

    private boolean publish(OutboxRow row) {
        try {
            jdbc.update("UPDATE event_outbox SET attempts = attempts + 1, updated_at = now() WHERE event_id = ?",
                    row.eventId());
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
            jdbc.update("""
                    UPDATE event_outbox SET published_at = now(), last_error = NULL, updated_at = now()
                    WHERE event_id = ? AND published_at IS NULL
                    """, row.eventId());
            published.increment();
            return true;
        } catch (Exception exception) {
            failures.increment();
            jdbc.update("""
                    UPDATE event_outbox SET
                        last_error = ?, updated_at = now()
                    WHERE event_id = ? AND published_at IS NULL
                    """, safeMessage(exception), row.eventId());
            return false;
        }
    }

    private String safeMessage(Exception exception) {
        String value = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    record OutboxRow(UUID eventId, String message) {}
}
