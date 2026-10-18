package dev.relaybox.worker;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class DeliveryMetrics {
    private final JdbcTemplate jdbc;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong inFlight = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();

    public DeliveryMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        registry.gauge("relaybox.delivery.queue.depth", Tags.of("status", "pending"), pending);
        registry.gauge("relaybox.delivery.queue.depth", Tags.of("status", "in_flight"), inFlight);
        registry.gauge("relaybox.delivery.queue.depth", Tags.of("status", "dead"), dead);
    }

    @Scheduled(fixedDelayString = "${relaybox.metrics.refresh-interval-ms:10000}", initialDelay = 1000)
    public void refreshQueueDepth() {
        Map<String, Long> counts = jdbc.query("""
                SELECT status, count(*) AS total
                FROM webhook_delivery
                WHERE status IN ('PENDING', 'IN_FLIGHT', 'DEAD')
                GROUP BY status
                """, rs -> {
            Map<String, Long> result = new HashMap<>();
            while (rs.next()) result.put(rs.getString("status"), rs.getLong("total"));
            return result;
        });
        pending.set(counts.getOrDefault("PENDING", 0L));
        inFlight.set(counts.getOrDefault("IN_FLIGHT", 0L));
        dead.set(counts.getOrDefault("DEAD", 0L));
    }
}
