package dev.relaybox.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.List;
import java.util.UUID;

/** Redis token bucket that smooths short event bursts before the durable PostgreSQL quota is checked. */
@Service
public class TenantBurstRateLimiter {
    private static final DefaultRedisScript<List> TOKEN_BUCKET = new DefaultRedisScript<>("""
            local now_parts = redis.call('TIME')
            local now_ms = (tonumber(now_parts[1]) * 1000) + math.floor(tonumber(now_parts[2]) / 1000)
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'updated_at_ms')
            local capacity = tonumber(ARGV[1])
            local refill_per_ms = tonumber(ARGV[2])
            local tokens = tonumber(state[1])
            local updated_at_ms = tonumber(state[2])
            if tokens == nil then
                tokens = capacity
                updated_at_ms = now_ms
            else
                local elapsed_ms = math.max(0, now_ms - updated_at_ms)
                tokens = math.min(capacity, tokens + (elapsed_ms * refill_per_ms))
            end
            local allowed = 0
            local retry_after_ms = 0
            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            else
                retry_after_ms = math.ceil((1 - tokens) / refill_per_ms)
            end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'updated_at_ms', now_ms)
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return {allowed, math.floor(tokens), retry_after_ms}
            """, List.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry meters;
    private final boolean enabled;
    private final int burstWindowSeconds;

    public TenantBurstRateLimiter(StringRedisTemplate redis, MeterRegistry meters,
                                  @Value("${relaybox.rate-limit.redis-burst.enabled:false}") boolean enabled,
                                  @Value("${relaybox.rate-limit.redis-burst.burst-window-seconds:10}") int burstWindowSeconds) {
        this.redis = redis;
        this.meters = meters;
        this.enabled = enabled;
        this.burstWindowSeconds = Math.max(1, Math.min(60, burstWindowSeconds));
    }

    public Decision acquire(UUID tenantId, int limitPerMinute) {
        if (!enabled) return Decision.disabled();

        int capacity = Math.max(1, (int) Math.ceil(limitPerMinute * (burstWindowSeconds / 60.0)));
        double refillPerMs = limitPerMinute / 60_000.0;
        long ttlMs = Math.max(30_000L, burstWindowSeconds * 2_000L);
        try {
            List<?> result = redis.execute(TOKEN_BUCKET,
                    List.of("relaybox:rate-limit:burst:v1:" + tenantId),
                    Integer.toString(capacity), Double.toString(refillPerMs), Long.toString(ttlMs));
            if (result == null || result.size() != 3 || result.get(0) == null) {
                throw new IllegalStateException("Redis returned an invalid token bucket response");
            }
            boolean allowed = numericValue(result.get(0)) == 1L;
            int remaining = Math.toIntExact(numericValue(result.get(1)));
            int retryAfterSeconds = (int) Math.max(1, Math.ceil(numericValue(result.get(2)) / 1000.0));
            meters.counter("relaybox.rate_limit.redis_burst.requests", "outcome", allowed ? "allowed" : "rejected").increment();
            return new Decision(true, allowed, remaining, retryAfterSeconds);
        } catch (RuntimeException exception) {
            // PostgreSQL's fixed-window quota remains authoritative if Redis is unavailable.
            meters.counter("relaybox.rate_limit.redis_burst.errors", "mode", "fail_open").increment();
            return new Decision(true, true, -1, 0);
        }
    }

    private long numericValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(value.toString());
    }

    public record Decision(boolean enabled, boolean allowed, int remaining, int retryAfterSeconds) {
        private static Decision disabled() { return new Decision(false, true, -1, 0); }
    }
}
