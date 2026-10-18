package dev.relaybox;

import dev.relaybox.service.DeliveryQueue;
import dev.relaybox.service.DeliveryQueue.DeliveryTask;
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
        for (int index = 0; index < deliveryCount; index++) insertDelivery(4);

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Callable<List<DeliveryTask>> claim = queue::claimBatch;
            Future<List<DeliveryTask>> first = workers.submit(claim);
            Future<List<DeliveryTask>> second = workers.submit(claim);
            List<DeliveryTask> claimed = new ArrayList<>(first.get());
            claimed.addAll(second.get());

            assertThat(claimed).hasSize(8);
            assertThat(claimed.stream().map(DeliveryTask::deliveryId).distinct()).hasSize(8);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE endpoint_id = ? AND status = 'PENDING'",
                    Integer.class, endpointId))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM webhook_delivery_attempt a
                    JOIN webhook_delivery d ON d.id = a.delivery_id
                    WHERE d.endpoint_id = ?
                    """, Integer.class, endpointId))
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

    private UUID insertDelivery(int maxAttempts) {
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO webhook_event (id, tenant_id, event_type, idempotency_key, payload)
                VALUES (?, ?, 'order.created', ?, '{\"orderId\":\"q-1\"}'::jsonb)
                """, eventId, tenantId, "queue-test-" + eventId);
        jdbc.update("""
                INSERT INTO webhook_delivery (id, event_id, endpoint_id, max_attempts)
                VALUES (?, ?, ?, ?)
                """, deliveryId, eventId, endpointId, maxAttempts);
        return deliveryId;
    }
}
