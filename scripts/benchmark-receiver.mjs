import http from "node:http";

const port = Number(process.env.BENCH_RECEIVER_PORT ?? 18089);
const delayMs = Number(process.env.BENCH_RECEIVER_DELAY_MS ?? 0);
const failEvery = Number(process.env.BENCH_RECEIVER_FAIL_EVERY ?? 0);
if (!Number.isInteger(delayMs) || delayMs < 0 || delayMs > 30000 ||
    !Number.isInteger(failEvery) || failEvery < 0) {
  throw new Error("BENCH_RECEIVER_DELAY_MS must be 0–30000 and BENCH_RECEIVER_FAIL_EVERY must be a nonnegative integer");
}
let received = 0;
let bytes = 0;
let failed = 0;

const server = http.createServer((request, response) => {
  if (request.method === "GET" && request.url === "/health") {
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ status: "UP", delayMs, failEvery }));
    return;
  }
  if (request.method === "GET" && request.url === "/stats") {
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ received, bytes, failed }));
    return;
  }
  if (request.method !== "POST" || request.url !== "/hook") {
    response.writeHead(404);
    response.end();
    return;
  }

  request.on("data", (chunk) => {
    bytes += chunk.length;
  });
  request.on("end", () => {
    received += 1;
    const status = failEvery > 0 && received % failEvery === 0 ? 503 : 200;
    if (status === 503) failed += 1;
    setTimeout(() => {
      response.writeHead(status);
      response.end();
    }, delayMs);
  });
});

server.listen(port, "0.0.0.0", () => {
  process.stdout.write(`Benchmark receiver listening on ${port} (delay=${delayMs}ms, failEvery=${failEvery})\n`);
});
