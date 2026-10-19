# Service boundaries and event handoff

RelayBox runs two independently deployable Spring Boot services:

- **Event API** owns tenants, API keys, endpoint subscriptions, accepted events and the transactional outbox in the `relaybox` PostgreSQL database.
- **Delivery Service** owns its endpoint delivery snapshots, delivery attempts, retry state, circuit breakers and worker queue in the separate `relaybox_delivery` PostgreSQL database.

RabbitMQ carries accepted-event messages between the two services. The Delivery Service's internal HTTP endpoints are only used by the Event API to read delivery state and request replay; they require the shared internal service token and are not published to the host in Compose.

## Event delivery protocol

1. The Event API validates the tenant key and event idempotency key. In one PostgreSQL transaction it inserts the event and a JSON outbox message containing the event plus the matching endpoint snapshots. If RabbitMQ is unavailable, the API can still accept the event because the outbox is durable.
2. One scheduled publisher at a time claims outbox rows in monotonically assigned dispatch order, sends a persistent message, waits for RabbitMQ publisher confirmation and only then marks the row published. A failed send leaves the row pending for a later retry.
3. RabbitMQ routes messages through a durable queue configured for a single active consumer. This preserves arrival order for this queue and allows replacement Delivery Service replicas to take over if the active instance stops.
4. The consumer inserts an inbox key, the event snapshot and delivery rows in one Delivery Service database transaction. The unique inbox event ID makes redelivery harmless. The message is acknowledged after the transaction commits. Database write failures get up to five bounded exponential-backoff retries; malformed messages or exhausted database retries go to the durable dead-letter queue.
5. Delivery workers claim rows from the Delivery Service database with `FOR UPDATE SKIP LOCKED`, leases and per-endpoint sequence numbers. Delivery state and retries remain owned by this service.

The guarantee is **at least once across RabbitMQ plus idempotent consumption**, not end-to-end exactly once. A publisher can crash after the broker accepts a message but before the outbox row is marked published; the same message will be sent again. The inbox key absorbs that duplicate. An event API response means the event and outbox record are durable; delivery rows may appear shortly afterward because service handoff is asynchronous.

Endpoint URL and signing-secret snapshots are included in the RabbitMQ message so delivery can proceed if the Event API is temporarily offline. Protect the broker and internal network, rotate credentials for deployed environments, and avoid logging message bodies. The local Compose credentials are development defaults only.

## Failure boundaries

- **RabbitMQ unavailable:** event requests continue to commit to the Event API outbox; publishing resumes when RabbitMQ returns. Outbox rows and oldest-row age should be monitored.
- **Delivery Service unavailable:** RabbitMQ retains accepted messages; replacement replicas consume them after recovery. Existing delivery work is durable in its own database.
- **Consumer crashes after commit but before acknowledgement:** RabbitMQ redelivers; the inbox uniqueness constraint prevents duplicate delivery creation.
- **Webhook receiver unavailable:** delivery retries and circuit state are handled entirely in the Delivery Service database.
- **Delivery state reads:** the public API proxies them to the Delivery Service. The local management UI's queue metrics are refreshed through an internal stats endpoint; if that service is unavailable, the last sample is retained and a proxy error counter increments.

## Local deployment

```powershell
docker compose up --build -d
docker compose ps
docker compose logs -f app delivery-service
```

Scale the Delivery Service (its Rabbit listener remains single-active while its PostgreSQL workers compete via `SKIP LOCKED`):

```powershell
docker compose up -d --scale delivery-service=3 delivery-service
```

The old API database schema still contains delivery tables for local legacy data and the integration-test compatibility path. New Compose-mode deliveries are written only to `relaybox_delivery`; existing legacy delivery records remain readable through a fallback. Historical throughput measurements were taken before this split and must not be presented as measurements of the RabbitMQ architecture.
