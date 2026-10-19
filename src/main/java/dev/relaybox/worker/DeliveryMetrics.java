package dev.relaybox.worker;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import dev.relaybox.service.DeliveryServiceClient;
import org.springframework.beans.factory.annotation.Value;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class DeliveryMetrics {
    private final JdbcTemplate jdbc;
    private final DeliveryServiceClient deliveryService;
    private final boolean deliveryServiceEnabled;
    private final MeterRegistry registry;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong inFlight = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();

    public DeliveryMetrics(JdbcTemplate jdbc, MeterRegistry registry, DeliveryServiceClient deliveryService,
                           @Value("${relaybox.delivery-service.enabled:false}") boolean deliveryServiceEnabled) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.deliveryService = deliveryService;
        this.deliveryServiceEnabled = deliveryServiceEnabled;
        registry.gauge("relaybox.delivery.queue.depth", Tags.of("status", "pending"), pending);
        registry.gauge("relaybox.delivery.queue.depth", Tags.of("status", "in_flight"), inFlight);
        registry.gauge("relaybox.delivery.queue.depth", Tags.of("status", "dead"), dead);
        registry.gauge("relaybox.delivery.queue.oldest.pending.age", oldestPendingAgeSeconds);
    }

    @Scheduled(fixedDelayString = "${relaybox.metrics.refresh-interval-ms:10000}", initialDelay = 1000)
    public void refreshQueueDepth() {
        if (deliveryServiceEnabled) {
            try {
                DeliveryServiceClient.QueueStats stats = deliveryService.queueStats();
                pending.set(stats.pending());
                inFlight.set(stats.inFlight());
                dead.set(stats.dead());
                oldestPendingAgeSeconds.set(stats.oldestPendingAgeSeconds());
            } catch (org.springframework.web.client.RestClientException exception) {
                registry.counter("relaybox.delivery.metrics.proxy.errors").increment();
            }
            return;
        }
        Map<String, Long> counts = jdbc.query("""
                SELECT status, count(*) AS total
                FROM webhook_delivery
                WHERE status IN ('PENDING', 'IN_FLIGHT', 'DEAD')
                GROUP BY status
                """, rs -> {
            Map<String, Long> result = new HashMap<>();
            while (rs.next()) {
                result.put(rs.getString("status"), rs.getLong("total"));
            }
            return result;
        });
        pending.set(counts.getOrDefault("PENDING", 0L));
        inFlight.set(counts.getOrDefault("IN_FLIGHT", 0L));
        dead.set(counts.getOrDefault("DEAD", 0L));
        Long oldestAge = jdbc.queryForObject("""
                SELECT coalesce((
                    SELECT floor(extract(epoch FROM now() - created_at))::bigint
                    FROM webhook_delivery
                    WHERE status = 'PENDING'
                    ORDER BY created_at
                    LIMIT 1
                ), 0)
                """, Long.class);
        oldestPendingAgeSeconds.set(oldestAge == null ? 0 : oldestAge);
    }
}
