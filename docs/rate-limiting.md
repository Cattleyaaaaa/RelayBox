# Tenant event rate limiting

RelayBox applies two limits to `POST /api/v1/events`:

1. A Redis token bucket smooths short bursts. Its capacity is `ceil(eventsPerMinute * burstWindowSeconds / 60)`, and it refills at `eventsPerMinute / 60` tokens per second. The default burst window is 10 seconds.
2. PostgreSQL enforces the tenant's fixed per-minute hard quota. It is durable and shared across app instances.

The Redis bucket is an admission guard, not the delivery queue. Accepted events and deliveries remain in PostgreSQL. A Redis rejection returns HTTP 429 with `Retry-After` and `X-RateLimit-Burst-Remaining`; the existing PostgreSQL headers continue to report the fixed-window quota.

## Redis failure behavior

If Redis is unavailable or returns an invalid response, the request continues to the PostgreSQL limiter. This fail-open behavior removes short-burst smoothing temporarily while preserving the durable per-minute cap. The number of fail-open decisions is exposed as `relaybox.rate_limit.redis_burst.errors` with `mode=fail_open`.

The token bucket uses Redis server time and one Lua script, so concurrent app instances update each tenant's bucket atomically. Bucket keys expire after inactivity; an idle tenant starts with a full bucket. Redis is configured as ephemeral in the local Compose setup because losing these short-lived tokens must not lose accepted event data.

## Configuration and signals

- `REDIS_BURST_LIMITER_ENABLED`: enable the Redis guard (Compose enables it; standalone/local runs default to disabled).
- `REDIS_BURST_WINDOW_SECONDS`: allowed burst size as seconds of the tenant's sustained rate, clamped to 1–60 seconds (default 10).
- `relaybox.rate_limit.redis_burst.requests{outcome=allowed|rejected}`: token-bucket decisions.
- `relaybox.rate_limit.redis_burst.errors{mode=fail_open}`: Redis failures that fell back to PostgreSQL.
- `X-RateLimit-Burst-Remaining`: whole tokens remaining after this request, when Redis supplied a valid decision.

Tune the burst window against a measured event-ingest workload. This setting shapes request arrival; it does not increase the PostgreSQL per-minute quota or imply production capacity.
