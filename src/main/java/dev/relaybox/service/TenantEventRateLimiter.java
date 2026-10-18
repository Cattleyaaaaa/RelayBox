package dev.relaybox.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** A database-backed fixed-window limiter shared by all application instances. */
@Service
public class TenantEventRateLimiter {
    private final JdbcTemplate jdbc;

    public TenantEventRateLimiter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Decision acquire(UUID tenantId, int limitPerMinute) {
        Counter counter = jdbc.queryForObject("""
                INSERT INTO tenant_event_rate_limit (tenant_id, window_started_at, request_count)
                VALUES (?, date_trunc('minute', now()), 1)
                ON CONFLICT (tenant_id) DO UPDATE SET
                    request_count = CASE
                        WHEN tenant_event_rate_limit.window_started_at < EXCLUDED.window_started_at THEN 1
                        WHEN tenant_event_rate_limit.request_count <= ? THEN tenant_event_rate_limit.request_count + 1
                        ELSE tenant_event_rate_limit.request_count
                    END,
                    window_started_at = EXCLUDED.window_started_at,
                    updated_at = now()
                RETURNING request_count, window_started_at,
                    GREATEST(1, CEIL(EXTRACT(EPOCH FROM
                        (window_started_at + INTERVAL '1 minute' - now())))::integer) AS retry_after_seconds
                """, (rs, row) -> new Counter(
                rs.getInt("request_count"), rs.getTimestamp("window_started_at").toInstant(),
                rs.getInt("retry_after_seconds")), tenantId, limitPerMinute);

        boolean allowed = counter.requestCount() <= limitPerMinute;
        int remaining = Math.max(0, limitPerMinute - counter.requestCount());
        return new Decision(allowed, limitPerMinute, remaining, counter.retryAfterSeconds(), counter.windowStartedAt());
    }

    private record Counter(int requestCount, Instant windowStartedAt, int retryAfterSeconds) {}

    public record Decision(boolean allowed, int limit, int remaining,
                           int retryAfterSeconds, Instant windowStartedAt) {}
}
