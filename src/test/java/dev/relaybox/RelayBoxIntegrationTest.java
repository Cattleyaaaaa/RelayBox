package dev.relaybox;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.relaybox.security.WebhookTargetPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@Execution(ExecutionMode.SAME_THREAD)
class RelayBoxIntegrationTest {
    private static final String MASTER_KEY = "test-master-key";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetReceiver() {
        assertThat(call("POST", "/dev/receiver/reset", MASTER_KEY, "").status()).isEqualTo(200);
    }

    @Test
    void repeatedIdempotencyKeyCreatesOnlyOneEventAndDelivery() {
        Tenant tenant = createTenant("idem");
        createEndpoint(tenant.apiKey());

        String idempotencyKey = "same-key-" + UUID.randomUUID();
        JsonNode first = publish(tenant.apiKey(), idempotencyKey);
        JsonNode repeated = publish(tenant.apiKey(), idempotencyKey);

        assertThat(first.path("duplicate").asBoolean()).isFalse();
        assertThat(repeated.path("eventId").asText()).isEqualTo(first.path("eventId").asText());
        assertThat(repeated.path("duplicate").asBoolean()).isTrue();
        assertThat(repeated.path("deliveryCount").asInt()).isEqualTo(1);
        assertThat(get("/api/v1/events/" + first.path("eventId").asText() + "/deliveries", tenant.apiKey())
                .body().path("items").size()).isEqualTo(1);
    }

    @Test
    void chaosReceiverPersistsOneBusinessEffectForDuplicateDeliveryRequests() {
        assertThat(call("POST", "/dev/receiver/chaos/arm", MASTER_KEY, "").status()).isEqualTo(200);
        UUID deliveryId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String body = "{\"type\":\"order.created\",\"data\":{\"orderId\":\"dedupe\"}}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/dev/receiver/chaos/deliver"))
                .header("X-Webhook-Delivery", deliveryId.toString())
                .header("X-Webhook-Id", eventId.toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();

        try {
            assertThat(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            assertThat(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
        } catch (Exception exception) {
            throw new IllegalStateException("Chaos receiver request failed", exception);
        }

        JsonNode state = get("/dev/receiver/chaos/state", MASTER_KEY).body();
        assertThat(state.path("receivedCount").asInt()).isEqualTo(2);
        assertThat(state.path("appliedBusinessEffects").asInt()).isEqualTo(1);
        assertThat(state.path("receipts").get(0).path("businessEffectApplied").asBoolean()).isTrue();
        assertThat(state.path("receipts").get(1).path("businessEffectApplied").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dev_receiver_effect WHERE delivery_id = ?",
                Integer.class, deliveryId)).isEqualTo(1);
        assertThat(get("/dev/receiver/chaos/report", "invalid-key").status()).isEqualTo(401);
    }

    @Test
    void retriesFailedRequestsUntilReceiverSucceeds() {
        Tenant tenant = createTenant("retry");
        createEndpoint(tenant.apiKey());
        configureReceiver(2);

        JsonNode event = publish(tenant.apiKey(), "retry-" + UUID.randomUUID());
        JsonNode delivery = awaitDelivery(tenant.apiKey(), event.path("eventId").asText(), "SUCCEEDED", 3);
        JsonNode attempts = get("/api/v1/deliveries/" + delivery.path("id").asText() + "/attempts", tenant.apiKey())
                .body().path("items");

        assertThat(attempts).hasSize(3);
        assertThat(attempts.get(0).path("responseCode").asInt()).isEqualTo(500);
        assertThat(attempts.get(1).path("responseCode").asInt()).isEqualTo(500);
        assertThat(attempts.get(2).path("responseCode").asInt()).isEqualTo(200);

        JsonNode firstPage = get("/api/v1/deliveries/" + delivery.path("id").asText() + "/attempts?limit=2&offset=0",
                tenant.apiKey()).body();
        JsonNode lastPage = get("/api/v1/deliveries/" + delivery.path("id").asText() + "/attempts?limit=2&offset=2",
                tenant.apiKey()).body();
        assertThat(firstPage.path("items")).hasSize(2);
        assertThat(firstPage.path("hasMore").asBoolean()).isTrue();
        assertThat(lastPage.path("items")).hasSize(1);
        assertThat(lastPage.path("hasMore").asBoolean()).isFalse();
    }

    @Test
    void deadDeliveryCanBeReplayedAndKeepsItsAttemptHistory() {
        Tenant tenant = createTenant("replay");
        createEndpoint(tenant.apiKey());
        configureReceiver(3);

        JsonNode event = publish(tenant.apiKey(), "dead-" + UUID.randomUUID());
        JsonNode deadDelivery = awaitDelivery(tenant.apiKey(), event.path("eventId").asText(), "DEAD", 3);
        String deliveryId = deadDelivery.path("id").asText();
        assertThat(get("/api/v1/deliveries/" + deliveryId + "/attempts", tenant.apiKey()).body().path("items")).hasSize(3);

        configureReceiver(0);
        assertThat(call("POST", "/api/v1/deliveries/" + deliveryId + "/replay", tenant.apiKey(), "")
                .status()).isEqualTo(202);

        JsonNode replayed = awaitDelivery(tenant.apiKey(), event.path("eventId").asText(), "SUCCEEDED", 4);
        assertThat(replayed.path("attemptCount").asInt()).isEqualTo(4);
        assertThat(get("/api/v1/deliveries/" + deliveryId + "/attempts", tenant.apiKey()).body().path("items")).hasSize(4);
    }

    @Test
    void tenantCannotReadOrReplayAnotherTenantsDeliveries() {
        Tenant owner = createTenant("owner");
        Tenant other = createTenant("other");
        createEndpoint(owner.apiKey());
        String sharedKey = "tenant-scope-" + UUID.randomUUID();

        JsonNode ownerEvent = publish(owner.apiKey(), sharedKey);
        JsonNode otherEvent = publish(other.apiKey(), sharedKey);
        assertThat(otherEvent.path("eventId").asText()).isNotEqualTo(ownerEvent.path("eventId").asText());
        assertThat(otherEvent.path("deliveryCount").asInt()).isZero();

        JsonNode ownerDeliveries = get("/api/v1/events/" + ownerEvent.path("eventId").asText() + "/deliveries", owner.apiKey())
                .body().path("items");
        assertThat(ownerDeliveries).hasSize(1);
        String deliveryId = ownerDeliveries.get(0).path("id").asText();

        JsonNode hiddenDeliveries = get("/api/v1/events/" + ownerEvent.path("eventId").asText() + "/deliveries", other.apiKey())
                .body().path("items");
        assertThat(hiddenDeliveries).isEmpty();
        assertThat(call("POST", "/api/v1/deliveries/" + deliveryId + "/replay", other.apiKey(), "")
                .status()).isEqualTo(400);
    }

    @Test
    void tenantEventRateLimitIsAtomicAndIsolatedPerTenant() throws Exception {
        Tenant limited = createTenant("limited", 5);
        assertThat(limited.eventRateLimitPerMinute()).isEqualTo(5);
        ExecutorService callers = Executors.newFixedThreadPool(10);
        List<Future<Response<JsonNode>>> responses = new ArrayList<>();
        try {
            for (int index = 0; index < 20; index++) {
                String idempotencyKey = "limited-" + index + "-" + UUID.randomUUID();
                responses.add(callers.submit(() -> publishResult(limited.apiKey(), idempotencyKey)));
            }
            List<Response<JsonNode>> results = new ArrayList<>();
            for (Future<Response<JsonNode>> response : responses) results.add(response.get());

            assertThat(results.stream().filter(result -> result.status() == 202)).hasSize(5);
            List<Response<JsonNode>> rejected = results.stream().filter(result -> result.status() == 429).toList();
            assertThat(rejected).hasSize(15);
            assertThat(rejected).allSatisfy(result -> {
                assertThat(result.retryAfter()).isNotNull();
                assertThat(Integer.parseInt(result.retryAfter())).isBetween(1, 60);
                assertThat(result.rateLimit()).isEqualTo("5");
                assertThat(result.rateLimitRemaining()).isEqualTo("0");
            });
        } finally {
            callers.shutdownNow();
        }

        Tenant other = createTenant("unlimited");
        assertThat(publishResult(other.apiKey(), "other-" + UUID.randomUUID()).status()).isEqualTo(202);
    }

    @Test
    void publicWebhookPolicyRejectsLoopbackAndPrivateTargets() {
        WebhookTargetPolicy strictPolicy = new WebhookTargetPolicy(false);

        assertThatThrownBy(() -> strictPolicy.validate("http://127.0.0.1:8080/hook"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public IP");
        assertThatThrownBy(() -> strictPolicy.validate("http://[::1]:8080/hook"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public IP");
        assertThatThrownBy(() -> strictPolicy.validate("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP or HTTPS");
    }

    @Test
    void apiKeyRotationSupportsGracePeriodAndImmediateRevocation() {
        Tenant tenant = createTenant("key-rotation");

        Response<JsonNode> firstRotation = call("POST", "/api/v1/tenants/api-key/rotate", tenant.apiKey(),
                "{\"gracePeriodSeconds\":30}");
        assertThat(firstRotation.status()).isEqualTo(200);
        String rotatedKey = firstRotation.body().path("apiKey").asText();
        assertThat(rotatedKey).startsWith("rbx_").isNotEqualTo(tenant.apiKey());
        assertThat(firstRotation.body().path("oldKeyExpiresAt").asText()).isNotBlank();

        assertThat(get("/api/v1/endpoints", tenant.apiKey()).status()).isEqualTo(200);
        assertThat(get("/api/v1/endpoints", rotatedKey).status()).isEqualTo(200);
        assertThat(call("POST", "/api/v1/tenants/api-key/rotate", tenant.apiKey(),
                "{\"gracePeriodSeconds\":30}").status()).isEqualTo(401);

        jdbc.update("UPDATE tenant SET previous_api_key_expires_at = now() - interval '1 second' WHERE id = ?",
                UUID.fromString(tenant.id()));
        assertThat(get("/api/v1/endpoints", tenant.apiKey()).status()).isEqualTo(401);

        Response<JsonNode> immediateRotation = call("POST", "/api/v1/tenants/api-key/rotate", rotatedKey,
                "{\"gracePeriodSeconds\":0}");
        assertThat(immediateRotation.status()).isEqualTo(200);
        assertThat(immediateRotation.body().has("oldKeyExpiresAt")).isFalse();
        String finalKey = immediateRotation.body().path("apiKey").asText();
        assertThat(get("/api/v1/endpoints", rotatedKey).status()).isEqualTo(401);
        assertThat(get("/api/v1/endpoints", finalKey).status()).isEqualTo(200);
    }

    @Test
    void endpointCanBePausedAndResumedForFutureEvents() {
        Tenant tenant = createTenant("endpoint-lifecycle");
        Response<JsonNode> endpoint = call("POST", "/api/v1/endpoints", tenant.apiKey(),
                "{\"url\":\"http://localhost:" + port + "/dev/receiver/deliver\",\"eventTypes\":[\"order.created\"]}");
        String endpointId = endpoint.body().path("id").asText();

        Response<JsonNode> paused = call("PATCH", "/api/v1/endpoints/" + endpointId, tenant.apiKey(),
                "{\"enabled\":false}");
        assertThat(paused.status()).isEqualTo(200);
        assertThat(paused.body().path("enabled").asBoolean()).isFalse();
        assertThat(get("/api/v1/endpoints", tenant.apiKey()).body().get(0).path("enabled").asBoolean()).isFalse();

        JsonNode pausedEvent = publish(tenant.apiKey(), "paused-" + UUID.randomUUID());
        assertThat(pausedEvent.path("deliveryCount").asInt()).isZero();

        Response<JsonNode> resumed = call("PATCH", "/api/v1/endpoints/" + endpointId, tenant.apiKey(),
                "{\"enabled\":true}");
        assertThat(resumed.status()).isEqualTo(200);
        assertThat(resumed.body().path("enabled").asBoolean()).isTrue();
        JsonNode resumedEvent = publish(tenant.apiKey(), "resumed-" + UUID.randomUUID());
        assertThat(resumedEvent.path("deliveryCount").asInt()).isEqualTo(1);

        Tenant other = createTenant("endpoint-lifecycle-other");
        assertThat(call("PATCH", "/api/v1/endpoints/" + endpointId, other.apiKey(),
                "{\"enabled\":false}").status()).isEqualTo(400);
    }

    @Test
    void deliveryListingUsesBoundedStableOffsetPages() {
        Tenant tenant = createTenant("delivery-pages");
        for (int index = 0; index < 3; index++) createEndpoint(tenant.apiKey());
        JsonNode event = publish(tenant.apiKey(), "pages-" + UUID.randomUUID());
        assertThat(event.path("deliveryCount").asInt()).isEqualTo(3);

        String path = "/api/v1/events/" + event.path("eventId").asText() + "/deliveries";
        JsonNode firstPage = get(path + "?limit=2&offset=0", tenant.apiKey()).body();
        JsonNode lastPage = get(path + "?limit=2&offset=2", tenant.apiKey()).body();
        assertThat(firstPage.path("items")).hasSize(2);
        assertThat(firstPage.path("hasMore").asBoolean()).isTrue();
        assertThat(lastPage.path("items")).hasSize(1);
        assertThat(lastPage.path("hasMore").asBoolean()).isFalse();
        assertThat(get(path + "?limit=101&offset=0", tenant.apiKey()).status()).isEqualTo(400);
    }

    @Test
    void endpointCanBeUpdatedDeletedAndHaveItsSigningSecretRotated() {
        Tenant tenant = createTenant("endpoint-update");
        JsonNode created = call("POST", "/api/v1/endpoints", tenant.apiKey(),
                "{\"url\":\"http://localhost:" + port + "/dev/receiver/deliver\",\"eventTypes\":[\"order.created\"]}").body();
        String endpointId = created.path("id").asText();
        String originalSecret = created.path("signingSecret").asText();

        Response<JsonNode> updated = call("PUT", "/api/v1/endpoints/" + endpointId, tenant.apiKey(),
                "{\"eventTypes\":[\"order.paid\"],\"circuitFailureThreshold\":2}");
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().path("eventTypes").get(0).asText()).isEqualTo("order.paid");
        assertThat(updated.body().path("circuitFailureThreshold").asInt()).isEqualTo(2);
        assertThat(publish(tenant.apiKey(), "old-type-" + UUID.randomUUID()).path("deliveryCount").asInt()).isZero();

        Response<JsonNode> rotated = call("POST", "/api/v1/endpoints/" + endpointId + "/secret/rotate",
                tenant.apiKey(), "");
        assertThat(rotated.status()).isEqualTo(200);
        assertThat(rotated.body().path("signingSecret").asText())
                .isNotBlank().isNotEqualTo(originalSecret);

        assertThat(call("DELETE", "/api/v1/endpoints/" + endpointId, tenant.apiKey(), "").status()).isEqualTo(204);
        assertThat(get("/api/v1/endpoints", tenant.apiKey()).body()).isEmpty();
        assertThat(call("POST", "/api/v1/endpoints", tenant.apiKey(),
                "{\"url\":\"http://localhost:" + port + "/dev/receiver/deliver\",\"eventTypes\":[\"order.created\"]}")
                .status()).isEqualTo(201);
    }

    @Test
    void oversizedPayloadIsRejectedBeforePersistingAnEvent() {
        Tenant tenant = createTenant("payload-limit");
        String body = "{\"type\":\"order.created\",\"idempotencyKey\":\"" + UUID.randomUUID()
                + "\",\"payload\":{\"blob\":\"" + "x".repeat(300_000) + "\"}}";
        assertThat(call("POST", "/api/v1/events", tenant.apiKey(), body).status()).isEqualTo(400);
    }

    @Test
    void adminEndpointsRequireTheMasterKey() {
        Tenant tenant = createTenant("admin-scope");
        assertThat(get("/api/v1/admin/outbox", tenant.apiKey()).status()).isEqualTo(401);
        assertThat(get("/api/v1/admin/outbox", MASTER_KEY).status()).isEqualTo(200);
    }

    private Tenant createTenant(String prefix) {
        return createTenant(prefix, null);
    }

    private Tenant createTenant(String prefix, Integer eventRateLimitPerMinute) {
        String request = "{\"name\":\"" + prefix + "-" + UUID.randomUUID() + "\""
                + (eventRateLimitPerMinute == null ? "" : ",\"eventRateLimitPerMinute\":" + eventRateLimitPerMinute) + "}";
        JsonNode body = call("POST", "/api/v1/tenants", MASTER_KEY, request).body();
        return new Tenant(body.path("id").asText(), body.path("apiKey").asText(),
                body.path("eventRateLimitPerMinute").asInt());
    }

    private void createEndpoint(String apiKey) {
        Response<JsonNode> response = call("POST", "/api/v1/endpoints", apiKey,
                "{\"url\":\"http://localhost:" + port + "/dev/receiver/deliver\",\"eventTypes\":[\"order.created\"]}");
        assertThat(response.status()).isEqualTo(201);
    }

    private JsonNode publish(String apiKey, String idempotencyKey) {
        return publishResult(apiKey, idempotencyKey).body();
    }

    private Response<JsonNode> publishResult(String apiKey, String idempotencyKey) {
        return call("POST", "/api/v1/events", apiKey,
                "{\"type\":\"order.created\",\"idempotencyKey\":\"" + idempotencyKey
                        + "\",\"payload\":{\"orderId\":\"order-123\"}}");
    }

    private void configureReceiver(int failuresBeforeSuccess) {
        Response<JsonNode> response = call("POST", "/dev/receiver/config", MASTER_KEY,
                "{\"failuresBeforeSuccess\":" + failuresBeforeSuccess + ",\"failureStatus\":500}");
        assertThat(response.status()).isEqualTo(200);
    }

    private JsonNode awaitDelivery(String apiKey, String eventId, String expectedStatus, int expectedAttempts) {
        var result = new java.util.concurrent.atomic.AtomicReference<JsonNode>();
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            JsonNode deliveries = get("/api/v1/events/" + eventId + "/deliveries", apiKey).body().path("items");
            assertThat(deliveries).hasSize(1);
            JsonNode delivery = deliveries.get(0);
            assertThat(delivery.path("status").asText()).isEqualTo(expectedStatus);
            assertThat(delivery.path("attemptCount").asInt()).isEqualTo(expectedAttempts);
            result.set(delivery);
        });
        return result.get();
    }

    private Response<JsonNode> get(String path, String apiKey) {
        return call("GET", path, apiKey, null);
    }

    private Response<JsonNode> call(String method, String path, String apiKey, String body) {
        HttpRequest.BodyPublisher requestBody = body == null || body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-API-Key", apiKey)
                .header("Content-Type", "application/json")
                .method(method, requestBody).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode responseBody = response.body().isBlank() ? null : mapper.readTree(response.body());
            return new Response<>(response.statusCode(), responseBody,
                    response.headers().firstValue("Retry-After").orElse(null),
                    response.headers().firstValue("X-RateLimit-Limit").orElse(null),
                    response.headers().firstValue("X-RateLimit-Remaining").orElse(null));
        } catch (Exception exception) {
            throw new IllegalStateException("HTTP integration request failed: " + method + " " + path, exception);
        }
    }

    private record Response<T>(int status, T body, String retryAfter,
                               String rateLimit, String rateLimitRemaining) {}
    private record Tenant(String id, String apiKey, int eventRateLimitPerMinute) {}
}
