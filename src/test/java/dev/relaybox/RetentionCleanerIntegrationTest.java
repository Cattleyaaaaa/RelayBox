package dev.relaybox;

import dev.relaybox.retention.RetentionCleaner;
import dev.relaybox.retention.RetentionReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "relaybox.worker.enabled=false",
        "relaybox.retention.enabled=false"
})
@ActiveProfiles("integration")
class RetentionCleanerIntegrationTest {
    private static final int OLD_DAYS = 400;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RetentionCleaner cleaner;

    private UUID tenantId;
    private UUID endpointId;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE TABLE webhook_delivery_attempt, webhook_delivery, event_outbox, "
                + "webhook_event, webhook_endpoint, tenant CASCADE");
        tenantId = UUID.randomUUID();
        endpointId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, name, api_key_hash) VALUES (?, ?, ?)",
                tenantId, "retention-" + tenantId, randomApiKeyHash());
        jdbc.update("""
                INSERT INTO webhook_endpoint (id, tenant_id, url, signing_secret, event_types)
                VALUES (?, ?, 'https://example.com/hook', 'c2VjcmV0', '["order.created"]'::jsonb)
                """, endpointId, tenantId);
    }

    @Test
    void deletesExpiredTerminalHistoryAndKeepsActiveRows() {
        UUID terminalEvent = insertEvent(OLD_DAYS);
        UUID terminalDelivery = insertDelivery(terminalEvent, "SUCCEEDED", OLD_DAYS, 1);
        insertAttempt(terminalDelivery, OLD_DAYS);
        insertPublishedOutbox(terminalEvent, OLD_DAYS);

        UUID pendingEvent = insertEvent(OLD_DAYS);
        UUID pendingDelivery = insertDelivery(pendingEvent, "PENDING", OLD_DAYS, 2);
        insertPublishedOutbox(pendingEvent, OLD_DAYS);

        UUID unpublishedEvent = insertEvent(OLD_DAYS);
        insertPendingOutbox(unpublishedEvent, OLD_DAYS);

        UUID abandonedEvent = insertEvent(OLD_DAYS);
        insertAbandonedOutbox(abandonedEvent, OLD_DAYS);

        UUID recentEvent = insertEvent(0);
        insertPublishedOutbox(recentEvent, 0);

        RetentionReport report = cleaner.runOnce(false);

        assertThat(report.dryRun()).isFalse();
        assertThat(report.outboxPublished()).isEqualTo(2);
        assertThat(report.outboxAbandoned()).isEqualTo(1);
        assertThat(report.attempts()).isEqualTo(1);
        assertThat(report.deliveries()).isEqualTo(1);
        assertThat(report.events()).isEqualTo(2);

        assertThat(exists("webhook_event", terminalEvent)).isFalse();
        assertThat(exists("webhook_delivery", terminalDelivery)).isFalse();
        assertThat(count("webhook_delivery_attempt", "delivery_id = ?", terminalDelivery)).isZero();
        assertThat(count("event_outbox", "event_id = ?", terminalEvent)).isZero();

        assertThat(exists("webhook_event", pendingEvent)).isTrue();
        assertThat(exists("webhook_delivery", pendingDelivery)).isTrue();
        assertThat(count("event_outbox", "event_id = ?", pendingEvent)).isZero();

        assertThat(exists("webhook_event", unpublishedEvent)).isTrue();
        assertThat(count("event_outbox", "event_id = ?", unpublishedEvent)).isOne();

        assertThat(exists("webhook_event", abandonedEvent)).isFalse();
        assertThat(count("event_outbox", "event_id = ?", abandonedEvent)).isZero();

        assertThat(exists("webhook_event", recentEvent)).isTrue();
        assertThat(count("event_outbox", "event_id = ?", recentEvent)).isOne();
    }

    @Test
    void dryRunReportsWithoutDeleting() {
        UUID eventId = insertEvent(OLD_DAYS);
        insertPublishedOutbox(eventId, OLD_DAYS);

        RetentionReport dryRun = cleaner.runOnce(true);

        assertThat(dryRun.dryRun()).isTrue();
        assertThat(dryRun.outboxPublished()).isEqualTo(1);
        assertThat(exists("webhook_event", eventId)).isTrue();
        assertThat(count("event_outbox", "event_id = ?", eventId)).isOne();

        cleaner.runOnce(false);

        assertThat(exists("webhook_event", eventId)).isFalse();
        assertThat(count("event_outbox", "event_id = ?", eventId)).isZero();
    }

    private UUID insertEvent(int ageDays) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO webhook_event (id, tenant_id, event_type, idempotency_key, payload, created_at)
                VALUES (?, ?, 'order.created', ?, '{"orderId":"retention"}'::jsonb,
                        now() - (? * interval '1 day'))
                """, eventId, tenantId, "retention-" + eventId, ageDays);
        return eventId;
    }

    private UUID insertDelivery(UUID eventId, String status, int ageDays, int sequence) {
        UUID deliveryId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO webhook_delivery (id, event_id, endpoint_id, status, attempt_count,
                        max_attempts, sequence_number, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 3, ?, now() - (? * interval '1 day'),
                        now() - (? * interval '1 day'))
                """, deliveryId, eventId, endpointId, status, sequence, ageDays, ageDays);
        return deliveryId;
    }

    private void insertAttempt(UUID deliveryId, int ageDays) {
        jdbc.update("""
                INSERT INTO webhook_delivery_attempt (id, delivery_id, attempt_number, status,
                        response_code, started_at, completed_at)
                VALUES (?, ?, 1, 'SUCCEEDED', 200, now() - (? * interval '1 day'),
                        now() - (? * interval '1 day'))
                """, UUID.randomUUID(), deliveryId, ageDays, ageDays);
    }

    private void insertPublishedOutbox(UUID eventId, int ageDays) {
        jdbc.update("""
                INSERT INTO event_outbox (event_id, tenant_id, message, published_at, created_at, updated_at)
                VALUES (?, ?, '{}'::jsonb, now() - (? * interval '1 day'),
                        now() - (? * interval '1 day'), now() - (? * interval '1 day'))
                """, eventId, tenantId, ageDays, ageDays, ageDays);
    }

    private void insertPendingOutbox(UUID eventId, int ageDays) {
        jdbc.update("""
                INSERT INTO event_outbox (event_id, tenant_id, message, created_at, updated_at)
                VALUES (?, ?, '{}'::jsonb, now() - (? * interval '1 day'),
                        now() - (? * interval '1 day'))
                """, eventId, tenantId, ageDays, ageDays);
    }

    private void insertAbandonedOutbox(UUID eventId, int ageDays) {
        jdbc.update("""
                INSERT INTO event_outbox (event_id, tenant_id, message, abandoned_at,
                        created_at, updated_at)
                VALUES (?, ?, '{}'::jsonb, now() - (? * interval '1 day'),
                        now() - (? * interval '1 day'), now() - (? * interval '1 day'))
                """, eventId, tenantId, ageDays, ageDays, ageDays);
    }

    private boolean exists(String table, UUID id) {
        return count(table, "id = ?", id) > 0;
    }

    private int count(String table, String where, Object... args) {
        Integer value = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where,
                Integer.class, args);
        return value == null ? 0 : value;
    }

    private String randomApiKeyHash() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }
}
