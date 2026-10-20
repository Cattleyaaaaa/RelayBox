package dev.relaybox;

import dev.relaybox.service.DeliveryQueue;
import dev.relaybox.service.DeliveryQueue.DeliveryTask;
import dev.relaybox.service.WebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "relaybox.worker.batch-size=4",
        "relaybox.worker.poll-interval-ms=3600000"
})
@ActiveProfiles("integration")
class DeliveryQueueIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeliveryQueue queue;

    @Autowired
    private WebhookService webhookService;

    private UUID tenantId;
    private UUID endpointId;

    @BeforeEach
    void createQueueFixture() {
        jdbc.execute("TRUNCATE TABLE webhook_delivery_attempt, webhook_delivery, webhook_event, webhook_endpoint, tenant CASCADE");
        tenantId = UUID.randomUUID();
        endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, name, api_key_hash) VALUES (?, ?, ?)", tenantId,
                "queue-test-" + tenantId, UUID.randomUUID().toString().replace("-", "") + "00000000000000000000000000000000");
        jdbc.update("""
                INSERT INTO webhook_endpoint (id, tenant_id, url, signing_secret, event_types)
                VALUES (?, ?, 'https://example.com/hook', 'c2VjcmV0', '[\"order.created\"]'::jsonb)
                """, endpointId, tenantId);
        jdbc.update("""
                INSERT INTO webhook_event (id, tenant_id, event_type, idempotency_key, payload)
                VALUES (?, ?, 'order.created', ?, '{\"orderId\":\"q-1\"}'::jsonb)
                """, eventId, tenantId, "queue-test-" + eventId);
    }

    @Test
    void concurrentWorkersClaimDistinctDeliveries() throws Exception {
        int deliveryCount = 10;
        for (int index = 0; index < deliveryCount; index++) {
            UUID independentEndpoint = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO webhook_endpoint (id, tenant_id, url, signing_secret, event_types)
                    VALUES (?, ?, 'https://example.com/hook', 'c2VjcmV0', '[\"order.created\"]'::jsonb)
                    """, independentEndpoint, tenantId);
            insertDelivery(independentEndpoint, 4);
        }

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Callable<List<DeliveryTask>> claim = queue::claimBatch;
            Future<List<DeliveryTask>> first = workers.submit(claim);
            Future<List<DeliveryTask>> second = workers.submit(claim);
            List<DeliveryTask> claimed = new ArrayList<>(first.get());
            claimed.addAll(second.get());

            assertThat(claimed).hasSize(8);
            assertThat(claimed.stream().map(DeliveryTask::deliveryId).distinct()).hasSize(8);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE status = 'PENDING'",
                    Integer.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM webhook_delivery_attempt a
                    JOIN webhook_delivery d ON d.id = a.delivery_id
                    JOIN webhook_event ev ON ev.id = d.event_id
                    WHERE ev.tenant_id = ?
                    """, Integer.class, tenantId))
                    .isEqualTo(8);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void expiredLeaseClosesOldAttemptAndRequeuesDelivery() {
        UUID deliveryId = insertDelivery(3);

        List<DeliveryTask> firstAttempt = queue.claimBatch();
        assertThat(firstAttempt).hasSize(1);
        assertThat(firstAttempt.get(0).deliveryId()).isEqualTo(deliveryId);
        jdbc.update("UPDATE webhook_delivery SET lease_until = now() - interval '1 second' WHERE id = ?", deliveryId);

        List<DeliveryTask> recoveredAttempt = queue.claimBatch();

        assertThat(recoveredAttempt).hasSize(1);
        assertThat(recoveredAttempt.get(0).deliveryId()).isEqualTo(deliveryId);
        assertThat(recoveredAttempt.get(0).attemptCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_delivery_attempt WHERE delivery_id = ? AND attempt_number = 1",
                String.class, deliveryId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_delivery WHERE id = ?", String.class, deliveryId))
                .isEqualTo("IN_FLIGHT");
    }

    @Test
    void expiredLeaseAtAttemptLimitMovesDeliveryToDeadLetter() {
        UUID deliveryId = insertDelivery(1);

        assertThat(queue.claimBatch()).hasSize(1);
        jdbc.update("UPDATE webhook_delivery SET lease_until = now() - interval '1 second' WHERE id = ?", deliveryId);

        assertThat(queue.claimBatch()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_delivery WHERE id = ?", String.class, deliveryId))
                .isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_delivery_attempt WHERE delivery_id = ?",
                String.class, deliveryId)).isEqualTo("FAILED");
    }

    @Test
    void endpointCircuitOpensAndClosesAfterSuccessfulHalfOpenProbe() {
        UUID deliveryId = insertDelivery(3);
        jdbc.update("UPDATE webhook_endpoint SET circuit_failure_threshold = 1, circuit_open_duration_seconds = 30 WHERE id = ?", endpointId);

        DeliveryTask failedAttempt = queue.claimBatch().get(0);
        queue.finish(failedAttempt, 500, "receiver unavailable");

        assertThat(jdbc.queryForObject("SELECT circuit_state FROM webhook_endpoint WHERE id = ?", String.class, endpointId))
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT consecutive_failures FROM webhook_endpoint WHERE id = ?", Integer.class, endpointId))
                .isEqualTo(1);

        // Make both the cooldown and the normal retry delay elapsed without making the test wait.
        jdbc.update("UPDATE webhook_endpoint SET circuit_open_until = now() - interval '1 second' WHERE id = ?", endpointId);
        jdbc.update("UPDATE webhook_delivery SET next_attempt_at = now() WHERE id = ?", deliveryId);
        List<DeliveryTask> probe = queue.claimBatch();

        assertThat(probe).hasSize(1);
        assertThat(probe.get(0).attemptCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT circuit_state FROM webhook_endpoint WHERE id = ?", String.class, endpointId))
                .isEqualTo("HALF_OPEN");
        assertThat(queue.claimBatch()).isEmpty(); // The endpoint-level probe lease prevents a second probe.

        queue.finish(probe.get(0), 200, null);
        assertThat(jdbc.queryForObject("SELECT circuit_state FROM webhook_endpoint WHERE id = ?", String.class, endpointId))
                .isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT consecutive_failures FROM webhook_endpoint WHERE id = ?", Integer.class, endpointId))
                .isZero();
    }

    @Test
    void endpointDeliveryOrderIsPreservedAcrossRetryAndWorkerClaim() {
        UUID first = insertDelivery(3);
        UUID second = insertDelivery(3);
        UUID third = insertDelivery(3);

        List<DeliveryTask> firstClaim = queue.claimBatch();
        assertThat(firstClaim).extracting(DeliveryTask::deliveryId).containsExactly(first);
        queue.finish(firstClaim.get(0), 500, "temporary receiver failure");

        // A retry at the head of the endpoint queue must block later sequence numbers.
        jdbc.update("UPDATE webhook_delivery SET next_attempt_at = now() WHERE id = ?", first);
        List<DeliveryTask> retry = queue.claimBatch();
        assertThat(retry).extracting(DeliveryTask::deliveryId).containsExactly(first);
        queue.finish(retry.get(0), 200, null);

        List<DeliveryTask> next = queue.claimBatch();
        assertThat(next).extracting(DeliveryTask::deliveryId).containsExactly(second);
        assertThat(jdbc.queryForObject("SELECT sequence_number FROM webhook_delivery WHERE id = ?", Long.class, first))
                .isLessThan(jdbc.queryForObject("SELECT sequence_number FROM webhook_delivery WHERE id = ?", Long.class, second));
        assertThat(jdbc.queryForObject("SELECT sequence_number FROM webhook_delivery WHERE id = ?", Long.class, second))
                .isLessThan(jdbc.queryForObject("SELECT sequence_number FROM webhook_delivery WHERE id = ?", Long.class, third));
    }

    @Test
    void replayedDeliveryIsPlacedAtTheEndpointQueueTail() {
        UUID deadDelivery = insertDelivery(1);
        UUID nextDelivery = insertDelivery(3);
        jdbc.update("UPDATE webhook_delivery SET status = 'DEAD' WHERE id = ?", deadDelivery);

        webhookService.replay(tenantId, deadDelivery);

        assertThat(jdbc.queryForObject("SELECT sequence_number FROM webhook_delivery WHERE id = ?", Long.class, deadDelivery))
                .isEqualTo(3L);
        List<DeliveryTask> firstClaim = queue.claimBatch();
        assertThat(firstClaim).extracting(DeliveryTask::deliveryId).containsExactly(nextDelivery);
        queue.finish(firstClaim.get(0), 200, null);
        List<DeliveryTask> replayClaim = queue.claimBatch();
        assertThat(replayClaim).extracting(DeliveryTask::deliveryId).containsExactly(deadDelivery);
    }

    @Test
    void localCapacityDeferralReturnsRetryBudgetAndLeavesCircuitClosed() {
        UUID deliveryId = insertDelivery(2);
        DeliveryTask task = queue.claimBatch().get(0);

        queue.defer(task, "delivery worker is at capacity; task will be retried");

        assertThat(jdbc.queryForObject("SELECT status FROM webhook_delivery WHERE id = ?", String.class, deliveryId))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM webhook_delivery WHERE id = ?", Integer.class, deliveryId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT max_attempts FROM webhook_delivery WHERE id = ?", Integer.class, deliveryId))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT circuit_state FROM webhook_endpoint WHERE id = ?", String.class, endpointId))
                .isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT consecutive_failures FROM webhook_endpoint WHERE id = ?", Integer.class, endpointId))
                .isZero();

        jdbc.update("UPDATE webhook_delivery SET next_attempt_at = now() WHERE id = ?", deliveryId);
        DeliveryTask retry = queue.claimBatch().get(0);
        assertThat(retry.deliveryId()).isEqualTo(deliveryId);
        assertThat(retry.attemptCount()).isEqualTo(2);
    }

    private UUID insertDelivery(int maxAttempts) {
        return insertDelivery(endpointId, maxAttempts);
    }

    private UUID insertDelivery(UUID targetEndpointId, int maxAttempts) {
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO webhook_event (id, tenant_id, event_type, idempotency_key, payload)
                VALUES (?, ?, 'order.created', ?, '{\"orderId\":\"q-1\"}'::jsonb)
                """, eventId, tenantId, "queue-test-" + eventId);
        long sequence = jdbc.queryForObject("""
                UPDATE webhook_endpoint SET next_delivery_sequence = next_delivery_sequence + 1
                WHERE id = ? RETURNING next_delivery_sequence
                """, Long.class, targetEndpointId);
        jdbc.update("""
                INSERT INTO webhook_delivery (id, event_id, endpoint_id, sequence_number, max_attempts)
                VALUES (?, ?, ?, ?, ?)
                """, deliveryId, eventId, targetEndpointId, sequence, maxAttempts);
        return deliveryId;
    }
}
