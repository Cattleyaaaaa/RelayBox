export type Tenant = { id: string; name: string };
export type TenantCredentials = Tenant & { apiKey: string };
export type Endpoint = {
  id: string;
  url: string;
  eventTypes: string[];
  enabled: boolean;
  circuitState: string;
  consecutiveFailures: number;
  circuitOpenUntil: string | null;
  circuitFailureThreshold: number;
  circuitOpenDurationSeconds: number;
  createdAt: string;
  updatedAt: string;
};
export type Delivery = {
  id: string;
  eventId: string;
  endpointId: string;
  sequenceNumber: number;
  status: string;
  attemptCount: number;
  maxAttempts?: number;
  nextAttemptAt: string;
  lastStatusCode: number | null;
  lastError: string | null;
  createdAt: string;
  updatedAt: string;
  eventType?: string;
  eventCreatedAt?: string;
};
export type QueueDepth = { pending: number; inFlight: number; dead: number };
export type DeliveryAttempt = {
  id: string;
  attemptNumber: number;
  status: string;
  responseCode: number | null;
  error: string | null;
  durationMs: number | null;
  startedAt: string;
  completedAt: string | null;
};
export type ChaosReport = {
  receiver: {
    receivedCount: number;
    appliedBusinessEffects: number;
    receipts: {
      requestNumber: number;
      receivedAt: string;
      deliveryId: string;
      eventId: string;
      businessEffectApplied: boolean;
      idempotencyConflict: boolean;
      responseCode: number;
    }[];
  };
  deliveryId: string | null;
  deliveryStatus: string | null;
  attempts: {
    attemptNumber: number;
    status: string;
    responseCode: number | null;
    error: string | null;
    startedAt: string;
    completedAt: string | null;
  }[];
};

type Page<T> = { items: T[]; limit: number; offset: number; hasMore: boolean };
const request = async <T>(
  path: string,
  apiKey?: string,
  options: RequestInit = {},
): Promise<T> => {
  const headers = new Headers(options.headers);
  if (apiKey) headers.set("X-API-Key", apiKey);
  if (options.body) headers.set("Content-Type", "application/json");
  const response = await fetch(path, { ...options, headers });
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const body = (await response.json()) as {
        detail?: string;
        message?: string;
      };
      message = body.detail ?? body.message ?? message;
    } catch {
      /* keep the HTTP status */
    }
    throw new Error(message);
  }
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
};

export const getHealth = async () => {
  try {
    return (
      (await request<{ status: string }>("/actuator/health")).status === "UP"
    );
  } catch {
    return false;
  }
};
const metric = async (status: string) => {
  try {
    const response = await request<{
      measurements: { statistic: string; value: number }[];
    }>(`/actuator/metrics/relaybox.delivery.queue.depth?tag=status:${status}`);
    return Math.round(
      response.measurements.find((item) => item.statistic === "VALUE")?.value ??
        0,
    );
  } catch {
    return 0;
  }
};
export const getQueueDepth = async (): Promise<QueueDepth> => {
  const [pending, inFlight, dead] = await Promise.all([
    metric("pending"),
    metric("in_flight"),
    metric("dead"),
  ]);
  return { pending, inFlight, dead };
};
export const createTenant = (
  masterKey: string,
  name: string,
  eventRateLimitPerMinute = 120,
) =>
  request<TenantCredentials>("/api/v1/tenants", masterKey, {
    method: "POST",
    body: JSON.stringify({ name, eventRateLimitPerMinute }),
  });
export const getEndpoints = (key: string) =>
  request<Endpoint[]>("/api/v1/endpoints", key);
export const createEndpoint = (
  key: string,
  input: {
    url: string;
    eventTypes: string[];
    circuitFailureThreshold: number;
    circuitOpenDurationSeconds: number;
  },
) =>
  request<Endpoint>("/api/v1/endpoints", key, {
    method: "POST",
    body: JSON.stringify(input),
  });
export const setEndpointEnabled = (key: string, id: string, enabled: boolean) =>
  request(`/api/v1/endpoints/${id}`, key, {
    method: "PATCH",
    body: JSON.stringify({ enabled }),
  });
export const publishEvent = (
  key: string,
  input: { type: string; idempotencyKey: string; payload: unknown },
) =>
  request<{ eventId: string; deliveryCount: number; duplicate: boolean }>(
    "/api/v1/events",
    key,
    { method: "POST", body: JSON.stringify(input) },
  );
export const getDeliveries = async (key: string, eventId: string) =>
  (
    await request<Page<Delivery>>(
      `/api/v1/events/${eventId}/deliveries?limit=100&offset=0`,
      key,
    )
  ).items;
export const getAttempts = async (key: string, deliveryId: string) =>
  (
    await request<Page<DeliveryAttempt>>(
      `/api/v1/deliveries/${deliveryId}/attempts?limit=100&offset=0`,
      key,
    )
  ).items;
export const replayDelivery = (key: string, deliveryId: string) =>
  request<void>(`/api/v1/deliveries/${deliveryId}/replay`, key, {
    method: "POST",
  });
export const configureDemoReceiver = (
  masterKey: string,
  failuresBeforeSuccess: number,
  failureStatus = 500,
) =>
  request<{
    totalRequests: number;
    failuresRemaining: number;
    failureStatus: number;
  }>("/dev/receiver/config", masterKey, {
    method: "POST",
    body: JSON.stringify({ failuresBeforeSuccess, failureStatus }),
  });
export const getChaosReport = (masterKey: string) =>
  request<ChaosReport>("/dev/receiver/chaos/report", masterKey);
