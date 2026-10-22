package dev.relaybox.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Deletes terminal history that is older than the configured windows. Each table is
 * cleaned in small batches so a retention run never holds a long transaction or blocks
 * live delivery work. Pending/in-flight work, unpublished outbox rows and unreplayed
 * dead letters are deliberately never selected.
 */
@Service
public class RetentionCleaner {
    private static final Logger log = LoggerFactory.getLogger(RetentionCleaner.class);

    private static final String COUNT_OUTBOX_PUBLISHED =
            "SELECT count(*) FROM event_outbox WHERE published_at IS NOT NULL "
                    + "AND published_at < now() - (? * interval '1 day')";
    private static final String DELETE_OUTBOX_PUBLISHED = """
            DELETE FROM event_outbox WHERE event_id IN (
                SELECT event_id FROM event_outbox
                WHERE published_at IS NOT NULL AND published_at < now() - (? * interval '1 day')
                LIMIT ?
            )
            """;

    private static final String COUNT_OUTBOX_ABANDONED =
            "SELECT count(*) FROM event_outbox WHERE abandoned_at IS NOT NULL "
                    + "AND abandoned_at < now() - (? * interval '1 day')";
    private static final String DELETE_OUTBOX_ABANDONED = """
            DELETE FROM event_outbox WHERE event_id IN (
                SELECT event_id FROM event_outbox
                WHERE abandoned_at IS NOT NULL AND abandoned_at < now() - (? * interval '1 day')
                LIMIT ?
            )
            """;

    private static final String COUNT_ATTEMPTS = """
            SELECT count(*) FROM webhook_delivery_attempt a
            JOIN webhook_delivery d ON d.id = a.delivery_id
            WHERE d.status IN ('SUCCEEDED', 'DEAD')
              AND d.updated_at < now() - (? * interval '1 day')
            """;
    private static final String DELETE_ATTEMPTS = """
            DELETE FROM webhook_delivery_attempt WHERE id IN (
                SELECT a.id FROM webhook_delivery_attempt a
                JOIN webhook_delivery d ON d.id = a.delivery_id
                WHERE d.status IN ('SUCCEEDED', 'DEAD')
                  AND d.updated_at < now() - (? * interval '1 day')
                LIMIT ?
            )
            """;

    private static final String COUNT_DELIVERIES =
            "SELECT count(*) FROM webhook_delivery WHERE status IN ('SUCCEEDED', 'DEAD') "
                    + "AND updated_at < now() - (? * interval '1 day')";
    private static final String DELETE_DELIVERIES = """
            DELETE FROM webhook_delivery WHERE id IN (
                SELECT id FROM webhook_delivery
                WHERE status IN ('SUCCEEDED', 'DEAD')
                  AND updated_at < now() - (? * interval '1 day')
                LIMIT ?
            )
            """;

    private static final String COUNT_EVENTS = """
            SELECT count(*) FROM webhook_event e
            WHERE e.created_at < now() - (? * interval '1 day')
              AND NOT EXISTS (SELECT 1 FROM event_outbox o WHERE o.event_id = e.id)
              AND NOT EXISTS (SELECT 1 FROM webhook_delivery d WHERE d.event_id = e.id)
            """;
    private static final String DELETE_EVENTS = """
            DELETE FROM webhook_event WHERE id IN (
                SELECT e.id FROM webhook_event e
                WHERE e.created_at < now() - (? * interval '1 day')
                  AND NOT EXISTS (SELECT 1 FROM event_outbox o WHERE o.event_id = e.id)
                  AND NOT EXISTS (SELECT 1 FROM webhook_delivery d WHERE d.event_id = e.id)
                LIMIT ?
            )
            """;

    private final JdbcTemplate jdbc;
    private final RetentionProperties properties;

    public RetentionCleaner(JdbcTemplate jdbc, RetentionProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public RetentionReport runOnce(boolean dryRun) {
        int outboxPublished = clean(DELETE_OUTBOX_PUBLISHED, COUNT_OUTBOX_PUBLISHED,
                properties.outboxPublishedDays(), dryRun);
        int outboxAbandoned = clean(DELETE_OUTBOX_ABANDONED, COUNT_OUTBOX_ABANDONED,
                properties.outboxAbandonedDays(), dryRun);
        int attempts = clean(DELETE_ATTEMPTS, COUNT_ATTEMPTS, properties.deliveryTerminalDays(), dryRun);
        int deliveries = clean(DELETE_DELIVERIES, COUNT_DELIVERIES, properties.deliveryTerminalDays(), dryRun);
        int events = clean(DELETE_EVENTS, COUNT_EVENTS, properties.eventDays(), dryRun);
        RetentionReport report = new RetentionReport(dryRun, outboxPublished, outboxAbandoned,
                attempts, deliveries, events, 0, 0);
        log.info("Retention {}: {}", dryRun ? "dry-run" : "cleanup", report);
        return report;
    }

    private int clean(String deleteSql, String countSql, int configuredDays, boolean dryRun) {
        int days = Math.max(1, Math.min(3650, configuredDays));
        if (dryRun) {
            Integer count = jdbc.queryForObject(countSql, Integer.class, days);
            return count == null ? 0 : count;
        }
        int batchSize = Math.max(10, Math.min(5000, properties.batchSize()));
        int total = 0;
        while (true) {
            int deleted = jdbc.update(deleteSql, days, batchSize);
            total += deleted;
            if (deleted < batchSize) break;
        }
        return total;
    }
}
