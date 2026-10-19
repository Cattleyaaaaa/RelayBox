package dev.relaybox.delivery.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
public class InternalMetricsController {
    private final JdbcTemplate jdbc;
    public InternalMetricsController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/queue-stats")
    public QueueStats queueStats() {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
                       count(*) FILTER (WHERE status = 'IN_FLIGHT') AS in_flight,
                       count(*) FILTER (WHERE status = 'DEAD') AS dead,
                       coalesce(max(floor(extract(epoch FROM now() - created_at))::bigint)
                           FILTER (WHERE status = 'PENDING'), 0) AS oldest_pending_age_seconds
                FROM webhook_delivery
                """, (rs, row) -> new QueueStats(rs.getLong("pending"), rs.getLong("in_flight"),
                rs.getLong("dead"), rs.getLong("oldest_pending_age_seconds")));
    }

    public record QueueStats(long pending, long inFlight, long dead, long oldestPendingAgeSeconds) {}
}
