import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const projectDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const baseUrl = (process.env.RELAYBOX_API_URL ?? "http://127.0.0.1:8080").replace(/\/$/, "");
const masterKey = process.env.RELAYBOX_MASTER_KEY ?? "local-master-key";
const runId = randomUUID().replaceAll("-", "");
const eventType = `compose.e2e.${runId}`;
const eventIds = [];

function docker(...args) {
  return execFileSync("docker", args, {
    cwd: projectDir,
    encoding: "utf8",
    timeout: 20000,
    maxBuffer: 1024 * 1024,
  }).trim();
}

async function request(method, url, key, body) {
  let response;
  try {
    response = await fetch(url, {
      method,
      headers: {
        "X-API-Key": key,
        ...(body ? { "Content-Type": "application/json" } : {}),
      },
      ...(body ? { body: JSON.stringify(body) } : {}),
      signal: AbortSignal.timeout(5000),
    });
  } catch (error) {
    const cause = error.cause;
    const detail = cause ? ` (${cause.code ?? cause.name}: ${cause.message})` : "";
    throw new Error(`${method} ${url} failed: ${error.message}${detail}`, { cause: error });
  }
  const raw = await response.text();
  let parsed;
  try { parsed = raw ? JSON.parse(raw) : null; } catch { parsed = raw; }
  if (!response.ok) throw new Error(`${method} ${url} returned ${response.status}: ${raw.slice(0, 300)}`);
  return parsed;
}

const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

async function waitForApi(timeoutSeconds = 30) {
  const deadline = Date.now() + timeoutSeconds * 1000;
  let latestError;
  while (Date.now() < deadline) {
    try {
      const health = await request("GET", `${baseUrl}/actuator/health`, "");
      if (health.status === "UP") return;
      latestError = new Error(`API health is ${health.status}`);
    } catch (error) {
      latestError = error;
    }
    await sleep(500);
  }
  throw new Error(`API did not become reachable at ${baseUrl} within ${timeoutSeconds}s: ${latestError?.message ?? "unknown error"}`, {
    cause: latestError,
  });
}

function outboxRows() {
  const sql = `SELECT coalesce(json_agg(json_build_object(
      'attempts', o.attempts, 'published', o.published_at IS NOT NULL
    ) ORDER BY o.dispatch_order), '[]'::json)::text
    FROM event_outbox o JOIN webhook_event e ON e.id = o.event_id
    WHERE e.event_type = '${eventType}'`;
  const output = docker("compose", "exec", "-T", "postgres", "psql", "-X", "-A", "-t",
    "-U", "relaybox", "-d", "relaybox", "-c", sql);
  return JSON.parse(output);
}

async function waitForDeliveries(apiKey, expectedCount, timeoutSeconds = 45) {
  const deadline = Date.now() + timeoutSeconds * 1000;
  let latest = [];
  while (Date.now() < deadline) {
    latest = [];
    for (const eventId of eventIds) {
      const page = await request("GET",
        `${baseUrl}/api/v1/events/${eventId}/deliveries?limit=100&offset=0`, apiKey);
      latest.push(...page.items);
    }
    if (latest.length === expectedCount && latest.every((item) => item.status === "SUCCEEDED")) return latest;
    if (latest.some((item) => item.status === "DEAD")) {
      throw new Error(`A delivery entered DEAD: ${JSON.stringify(latest)}`);
    }
    await sleep(250);
  }
  throw new Error(`Timed out waiting for delivery recovery: ${JSON.stringify(latest)}`);
}

async function main() {
  await waitForApi();

  const tenant = await request("POST", `${baseUrl}/api/v1/tenants`, masterKey, {
    name: `compose-e2e-${runId}`,
    eventRateLimitPerMinute: 120,
  });
  await request("POST", `${baseUrl}/api/v1/endpoints`, tenant.apiKey, {
    url: "http://app:8080/dev/receiver/deliver",
    eventTypes: [eventType],
  });

  let brokerStopped = false;
  let attemptsDuringOutage;
  try {
    docker("compose", "stop", "rabbitmq");
    brokerStopped = true;
    for (let index = 0; index < 2; index += 1) {
      const event = await request("POST", `${baseUrl}/api/v1/events`, tenant.apiKey, {
        type: eventType,
        idempotencyKey: `compose-e2e-${runId}-${index}`,
        payload: { runId, sequence: index + 1 },
      });
      eventIds.push(event.eventId);
    }

    const deadline = Date.now() + 15000;
    while (Date.now() < deadline) {
      attemptsDuringOutage = outboxRows();
      if (attemptsDuringOutage.length === 2 && attemptsDuringOutage[0].attempts > 0 &&
          attemptsDuringOutage[1].attempts === 0 &&
          attemptsDuringOutage.every((row) => !row.published)) break;
      await sleep(250);
    }
    if (!attemptsDuringOutage || attemptsDuringOutage.length !== 2 ||
        attemptsDuringOutage[0].attempts < 1 || attemptsDuringOutage[1].attempts !== 0 ||
        attemptsDuringOutage.some((row) => row.published)) {
      throw new Error(`Unexpected Outbox state during broker outage: ${JSON.stringify(attemptsDuringOutage)}`);
    }
  } finally {
    if (brokerStopped) {
      docker("compose", "up", "-d", "--wait", "rabbitmq");
    }
  }

  const deliveries = await waitForDeliveries(tenant.apiKey, eventIds.length);
  if (deliveries.length !== 2 || deliveries.some((item) => item.attemptCount !== 1)) {
    throw new Error(`Unexpected recovered deliveries: ${JSON.stringify(deliveries)}`);
  }

  const report = {
    scenario: "compose-outbox-rabbitmq-inbox-delivery-recovery",
    runId,
    complete: true,
    eventsAcceptedDuringBrokerOutage: eventIds.length,
    outboxAttemptsDuringOutage: attemptsDuringOutage,
    deliveriesAfterRecovery: deliveries.map(({ id, status, attemptCount }) => ({ id, status, attemptCount })),
  };
  const resultsDir = path.join(projectDir, "benchmarks", "results");
  await mkdir(resultsDir, { recursive: true });
  const resultPath = path.join(resultsDir, `compose-e2e-${runId.slice(0, 12)}.json`);
  await writeFile(resultPath, `${JSON.stringify(report, null, 2)}\n`);
  process.stdout.write(`Compose E2E passed: 2 events survived a RabbitMQ outage and both deliveries succeeded.\n${resultPath}\n`);
}

main().catch((error) => {
  process.stderr.write(`${error.stack ?? error}\n`);
  process.exitCode = 1;
});
