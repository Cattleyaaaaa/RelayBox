package dev.relaybox.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Delivery-side retention. Only terminal delivery history is deleted; the inbox and
 * replayed dead letters have their own windows. Pending/in-flight deliveries, events
 * still referenced by a delivery and unreplayed dead letters are never selected.
 */
@Service
public class RetentionCleaner {
    private static final Logger log = LoggerFactory.getLogger(RetentionCleaner.class);

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
              AND NOT EXISTS (SELECT 1 FROM webhook_delivery d WHERE d.event_id = e.id)
            """;
    private static final String DELETE_EVENTS = """
            DELETE FROM webhook_event WHERE id IN (
                SELECT e.id FROM webhook_event e
                WHERE e.created_at < now() - (? * interval '1 day')
                  AND NOT EXISTS (SELECT 1 FROM webhook_delivery d WHERE d.event_id = e.id)
                LIMIT ?
            )
            """;

    private static final String COUNT_INBOX =
            "SELECT count(*) FROM processed_delivery_event "
                    + "WHERE processed_at < now() - (? * interval '1 day')";
    private static final String DELETE_INBOX = """
            DELETE FROM processed_delivery_event WHERE event_id IN (
                SELECT event_id FROM processed_delivery_event
                WHERE processed_at < now() - (? * interval '1 day')
                LIMIT ?
            )
            """;

    private static final String COUNT_DEAD_LETTERS =
            "SELECT count(*) FROM dead_letter_message WHERE replayed_at IS NOT NULL "
                    + "AND replayed_at < now() - (? * interval '1 day')";
    private static final String DELETE_DEAD_LETTERS = """
            DELETE FROM dead_letter_message WHERE id IN (
                SELECT id FROM dead_letter_message
                WHERE replayed_at IS NOT NULL AND replayed_at < now() - (? * interval '1 day')
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
        int attempts = clean(DELETE_ATTEMPTS, COUNT_ATTEMPTS, properties.deliveryTerminalDays(), dryRun);
        int deliveries = clean(DELETE_DELIVERIES, COUNT_DELIVERIES, properties.deliveryTerminalDays(), dryRun);
        int events = clean(DELETE_EVENTS, COUNT_EVENTS, properties.eventDays(), dryRun);
        int inbox = clean(DELETE_INBOX, COUNT_INBOX, properties.inboxDays(), dryRun);
        int deadLetters = clean(DELETE_DEAD_LETTERS, COUNT_DEAD_LETTERS,
                properties.deadLetterReplayedDays(), dryRun);
        RetentionReport report = new RetentionReport(dryRun, 0, 0, attempts, deliveries, events, inbox, deadLetters);
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
