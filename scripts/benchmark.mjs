import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { performance } from "node:perf_hooks";

const projectDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const argumentsByName = new Map(process.argv.slice(2).map((argument) => {
  const [name, value] = argument.split("=", 2);
  if (!name.startsWith("--") || value === undefined) {
    throw new Error(`Expected --name=value, received ${argument}`);
  }
  return [name.slice(2), value];
}));

function integerOption(name, fallback, minimum, maximum) {
  const value = Number(argumentsByName.get(name) ?? fallback);
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    throw new Error(`--${name} must be an integer from ${minimum} to ${maximum}`);
  }
  return value;
}

const rate = integerOption("rate", 0, 0, 400);
const durationSeconds = integerOption("duration-seconds", 0, 0, 600);
if ((rate === 0) !== (durationSeconds === 0)) {
  throw new Error("--rate and --duration-seconds must be supplied together");
}
const count = rate ? rate * durationSeconds : integerOption("count", 400, 1, 10000);
if (count > 10000) throw new Error("A benchmark run is capped at 10000 events");
const concurrency = integerOption("concurrency", 24, 1, 100);
const streams = integerOption("streams", 40, 1, 100);
const tenantCount = integerOption("tenants", 1, 1, 20);
if (tenantCount > streams) throw new Error("--tenants cannot exceed --streams");
if (count + streams > 10000) {
  throw new Error("Run size plus warmup events exceeds the tenant's 10000 requests/minute quota");
}
const timeoutSeconds = integerOption("timeout-seconds", 120, 10, 1800);
const apiUrl = (argumentsByName.get("api-url") ?? "http://localhost:8080").replace(/\/$/, "");
const receiverUrl = argumentsByName.get("receiver-url") ?? "http://host.docker.internal:18089/hook";
const adminKey = process.env.RELAYBOX_MASTER_KEY ?? "local-master-key";
const runId = randomUUID().replaceAll("-", "").slice(0, 12);
const eventPrefix = `bench.${runId}`;
const startedAt = new Date().toISOString();
let phase = "initialization";

function docker(...args) {
  return execFileSync("docker", args, {
    cwd: projectDir,
    encoding: "utf8",
    timeout: 15000,
    maxBuffer: 1024 * 1024,
  }).trim();
}

function databaseSnapshot(eventTypePattern) {
  const sql = `
    SELECT row_to_json(summary) FROM (
      SELECT count(DISTINCT e.id)::int AS events,
             count(d.id)::int AS deliveries,
             count(d.id) FILTER (WHERE d.status = 'SUCCEEDED')::int AS succeeded,
             count(d.id) FILTER (WHERE d.status = 'DEAD')::int AS dead,
             count(d.id) FILTER (WHERE d.status = 'PENDING')::int AS pending,
             count(d.id) FILTER (WHERE d.status = 'IN_FLIGHT')::int AS in_flight,
             coalesce(sum(d.attempt_count), 0)::int AS attempts,
             percentile_cont(0.5) WITHIN GROUP
               (ORDER BY extract(epoch FROM (d.updated_at - e.created_at)) * 1000)
               FILTER (WHERE d.status = 'SUCCEEDED') AS p50_delivery_ms,
             percentile_cont(0.95) WITHIN GROUP
               (ORDER BY extract(epoch FROM (d.updated_at - e.created_at)) * 1000)
               FILTER (WHERE d.status = 'SUCCEEDED') AS p95_delivery_ms,
             percentile_cont(0.99) WITHIN GROUP
               (ORDER BY extract(epoch FROM (d.updated_at - e.created_at)) * 1000)
               FILTER (WHERE d.status = 'SUCCEEDED') AS p99_delivery_ms,
             coalesce((SELECT floor(extract(epoch FROM now() - min(p.created_at)) * 1000)::bigint
                       FROM webhook_delivery p
                       JOIN webhook_event pe ON pe.id = p.event_id
                       WHERE pe.event_type LIKE '${eventTypePattern}' AND p.status = 'PENDING'), 0)
               AS oldest_pending_age_ms
      FROM webhook_event e
      LEFT JOIN webhook_delivery d ON d.event_id = e.id
      WHERE e.event_type LIKE '${eventTypePattern}'
    ) summary`;
  const output = docker("compose", "exec", "-T", "delivery-postgres", "psql", "-X", "-A", "-t",
    "-U", "relaybox_delivery", "-d", "relaybox_delivery", "-c", sql);
  return JSON.parse(output.split(/\r?\n/).find((line) => line.startsWith("{")));
}

function outboxSnapshot(eventTypePattern) {
  const sql = `SELECT row_to_json(s) FROM (
    SELECT count(*)::int AS events,
           count(*) FILTER (WHERE o.published_at IS NULL)::int AS unpublished,
           coalesce(max(extract(epoch FROM now() - o.created_at) * 1000)
             FILTER (WHERE o.published_at IS NULL), 0) AS oldest_unpublished_age_ms
    FROM webhook_event e JOIN event_outbox o ON o.event_id = e.id
    WHERE e.event_type LIKE '${eventTypePattern}'
  ) s`;
  return JSON.parse(docker("compose", "exec", "-T", "postgres", "psql", "-X", "-A", "-t",
    "-U", "relaybox", "-d", "relaybox", "-c", sql));
}

async function requestJson(url, key, body) {
  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-API-Key": key },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(15000),
  });
  const text = await response.text();
  let result;
  try { result = JSON.parse(text); } catch { result = { detail: text.slice(0, 200) }; }
  if (!response.ok) {
    throw new Error(`${response.status}: ${result.detail ?? result.message ?? response.statusText}`);
  }
  return result;
}

async function outboxMetrics() {
  const names = ["relaybox.outbox.batch.duration", "relaybox.outbox.confirm.duration",
    "relaybox.outbox.batch.size", "relaybox.outbox.messages.published",
    "relaybox.outbox.messages.failed", "relaybox.outbox.unpublished"];
  const result = {};
  for (const name of names) {
    const response = await fetch(`${apiUrl}/actuator/metrics/${name}`, {
      signal: AbortSignal.timeout(5000),
    });
    result[name] = response.ok ? (await response.json()).measurements : null;
  }
  return result;
}

function percentile(values, fraction) {
  if (!values.length) return null;
  const sorted = [...values].sort((left, right) => left - right);
  return Math.round(sorted[Math.ceil(sorted.length * fraction) - 1] * 100) / 100;
}

const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

async function publishBatch(apiKeys, amount, warmup) {
  let nextIndex = 0;
  let accepted = 0;
  const latencies = [];
  const errors = [];
  const started = performance.now();
  await Promise.all(Array.from({ length: Math.min(concurrency, amount) }, async () => {
    while (true) {
      const index = nextIndex++;
      if (index >= amount) break;
      if (!warmup && rate > 0) {
        await sleep(Math.max(0, started + index * 1000 / rate - performance.now()));
      }
      const streamIndex = index % streams;
      const eventType = `${eventPrefix}.${warmup ? "warmup" : "stream"}${streamIndex}`;
      const requestStart = performance.now();
      try {
        await requestJson(`${apiUrl}/api/v1/events`, apiKeys[streamIndex % apiKeys.length], {
          type: eventType,
          idempotencyKey: `${eventPrefix}-${warmup ? "warmup" : "run"}-${index}`,
          payload: { orderId: `bench-${runId}-${index}`, amount: 12900, currency: "CNY" },
        });
        accepted += 1;
        latencies.push(performance.now() - requestStart);
      } catch (error) {
        errors.push({ index, message: String(error) });
      }
    }
  }));
  return {
    attempted: amount,
    accepted,
    rejected: errors.length,
    errors: errors.slice(0, 20),
    publishSeconds: (performance.now() - started) / 1000,
    requestLatencyMs: latencies.map((value) => Math.round(value * 100) / 100),
  };
}

async function waitForDeliveries(pattern, expected, maxSeconds) {
  const deadline = Date.now() + maxSeconds * 1000;
  let snapshot;
  while (Date.now() < deadline) {
    snapshot = databaseSnapshot(pattern);
    if (snapshot.events === expected && snapshot.deliveries === expected &&
        snapshot.succeeded + snapshot.dead === expected) {
      return { snapshot, complete: true };
    }
    await sleep(1000);
  }
  return { snapshot, complete: false };
}

async function main() {
  const health = await fetch(`${apiUrl}/actuator/health`, { signal: AbortSignal.timeout(5000) });
  if (!health.ok) throw new Error(`RelayBox health returned ${health.status}`);
  const receiver = await fetch("http://127.0.0.1:18089/health", { signal: AbortSignal.timeout(5000) });
  if (!receiver.ok) throw new Error("Start node scripts/benchmark-receiver.mjs first");
  const receiverConfig = await receiver.json();

  const runningWorkers = docker("compose", "ps", "-q", "delivery-service").split(/\r?\n/).filter(Boolean).length;
  const workerEnvironment = docker("compose", "exec", "-T", "delivery-service", "env");
  const workerPollIntervalMs = Number(workerEnvironment.match(/^WORKER_POLL_INTERVAL_MS=(\d+)$/m)?.[1] ?? 1000);
  const workerBatchSize = Number(workerEnvironment.match(/^WORKER_BATCH_SIZE=(\d+)$/m)?.[1] ?? 20);
  const workerConcurrency = Number(workerEnvironment.match(/^WORKER_CONCURRENCY=(\d+)$/m)?.[1] ?? 8);
  const apiEnvironment = docker("compose", "exec", "-T", "app", "env");
  const outboxPollIntervalMs = Number(apiEnvironment.match(/^OUTBOX_POLL_INTERVAL_MS=(\d+)$/m)?.[1] ?? 250);
  const outboxBatchSize = Number(apiEnvironment.match(/^OUTBOX_BATCH_SIZE=(\d+)$/m)?.[1] ?? 20);
  const dockerCapacity = docker("info", "--format", "{{.NCPU}} {{.MemTotal}}").split(" ");
  const tenants = await Promise.all(Array.from({ length: tenantCount }, (_, index) =>
    requestJson(`${apiUrl}/api/v1/tenants`, adminKey, {
      name: `benchmark-${runId}-${index + 1}`,
      eventRateLimitPerMinute: 10000,
    })));
  for (let index = 0; index < streams; index += 1) {
    await requestJson(`${apiUrl}/api/v1/endpoints`, tenants[index % tenantCount].apiKey, {
      url: receiverUrl,
      eventTypes: [`${eventPrefix}.warmup${index}`, `${eventPrefix}.stream${index}`],
      circuitFailureThreshold: 1000,
    });
  }
  const apiKeys = Array.from({ length: tenantCount }, (_, index) => tenants[index].apiKey);
  process.stdout.write(`Created ${tenantCount} isolated tenant(s) and ${streams} endpoints; warming up...\n`);
  phase = "warmup";
  const warmup = await publishBatch(apiKeys, streams, true);
  const warmupDelivery = await waitForDeliveries(`${eventPrefix}.warmup%`, streams, timeoutSeconds);
  if (warmup.rejected || !warmupDelivery.complete || warmupDelivery.snapshot.dead) {
    throw new Error(`Warmup failed: ${JSON.stringify({ warmup, delivery: warmupDelivery.snapshot })}`);
  }

  const receiverBefore = await fetch("http://127.0.0.1:18089/stats").then((response) => response.json());
  const outboxMetricsBefore = await outboxMetrics();
  process.stdout.write(`Warmup succeeded. Publishing ${count} events at concurrency ${concurrency}...\n`);
  const started = performance.now();
  phase = "publish";
  const published = await publishBatch(apiKeys, count, false);
  phase = "observe";
  const outboxAtPublishEnd = outboxSnapshot(`${eventPrefix}.stream%`);
  const snapshotAtPublishEnd = databaseSnapshot(`${eventPrefix}.stream%`);
  const delivery = await waitForDeliveries(`${eventPrefix}.stream%`, published.accepted, timeoutSeconds);
  const endToEndSeconds = (performance.now() - started) / 1000;
  const receiverStats = await fetch("http://127.0.0.1:18089/stats").then((response) => response.json());
  const outboxMetricsAfter = await outboxMetrics();
  const complete = published.rejected === 0 && delivery.complete &&
    delivery.snapshot.dead === 0 && delivery.snapshot.succeeded === count;
  const report = {
    runId,
    startedAt,
    complete,
    environment: {
      platform: `${os.platform()} ${os.release()}`,
      hostLogicalCpus: os.cpus().length,
      hostMemoryGiB: Math.round(os.totalmem() / 1024 ** 3 * 10) / 10,
      dockerCpus: Number(dockerCapacity[0]),
      dockerMemoryGiB: Math.round(Number(dockerCapacity[1]) / 1024 ** 3 * 10) / 10,
      runningWorkers,
      tenantCount,
      workerPollIntervalMs,
      workerBatchSize,
      workerConcurrency,
      outboxPollIntervalMs,
      outboxBatchSize,
      receiver: "separate host Node.js process via host.docker.internal",
      receiverDelayMs: receiverConfig.delayMs,
      receiverFailEvery: receiverConfig.failEvery,
    },
    workload: { count, concurrency, streams, tenantCount, ratePerSecond: rate || null,
      durationSeconds: durationSeconds || null, tenantRateLimitPerMinute: 10000, receiverUrl },
    measurements: {
      handoff: { outboxAtPublishEnd, outboxMetricsBefore, outboxMetricsAfter },
      published: {
        attempted: published.attempted,
        accepted: published.accepted,
        rejected: published.rejected,
        seconds: Math.round(published.publishSeconds * 100) / 100,
        acceptedPerSecond: Math.round(published.accepted / published.publishSeconds * 100) / 100,
        p50LatencyMs: percentile(published.requestLatencyMs, 0.5),
        p95LatencyMs: percentile(published.requestLatencyMs, 0.95),
        p99LatencyMs: percentile(published.requestLatencyMs, 0.99),
        errors: published.errors,
      },
      delivery: {
        ...delivery.snapshot,
        complete: delivery.complete,
        atPublishEnd: snapshotAtPublishEnd,
        observedEndToEndSeconds: Math.round(endToEndSeconds * 100) / 100,
      },
      receiver: {
        receivedDuringRun: receiverStats.received - receiverBefore.received,
        failedDuringRun: receiverStats.failed - receiverBefore.failed,
        bytesDuringRun: receiverStats.bytes - receiverBefore.bytes,
      },
    },
    rawPublishLatencyMs: published.requestLatencyMs,
  };
  const outputDir = path.join(projectDir, "benchmarks", "results");
  await mkdir(outputDir, { recursive: true });
  const outputPath = path.join(outputDir, `${runId}.json`);
  await writeFile(outputPath, JSON.stringify(report, null, 2) + "\n");
  process.stdout.write(`${JSON.stringify({ runId, complete, published: report.measurements.published,
    delivery: report.measurements.delivery, outputPath }, null, 2)}\n`);
  if (!complete) process.exitCode = 1;
}

main().catch(async (error) => {
  const outputDir = path.join(projectDir, "benchmarks", "results");
  await mkdir(outputDir, { recursive: true });
  await writeFile(path.join(outputDir, `failed-${runId}.json`), JSON.stringify({
    runId, startedAt, complete: false, phase, error: String(error),
    workload: { rate, durationSeconds, count, concurrency, streams, tenantCount, receiverUrl },
  }, null, 2) + "\n");
  process.stderr.write(`${error.stack ?? error}\n`);
  process.exitCode = 1;
});
