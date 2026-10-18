package dev.relaybox;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
                .body().size()).isEqualTo(1);
    }

    @Test
    void retriesFailedRequestsUntilReceiverSucceeds() {
        Tenant tenant = createTenant("retry");
        createEndpoint(tenant.apiKey());
        configureReceiver(2);

        JsonNode event = publish(tenant.apiKey(), "retry-" + UUID.randomUUID());
        JsonNode delivery = awaitDelivery(tenant.apiKey(), event.path("eventId").asText(), "SUCCEEDED", 3);
        JsonNode attempts = get("/api/v1/deliveries/" + delivery.path("id").asText() + "/attempts", tenant.apiKey()).body();

        assertThat(attempts).hasSize(3);
        assertThat(attempts.get(0).path("responseCode").asInt()).isEqualTo(500);
        assertThat(attempts.get(1).path("responseCode").asInt()).isEqualTo(500);
        assertThat(attempts.get(2).path("responseCode").asInt()).isEqualTo(200);
    }

    @Test
    void deadDeliveryCanBeReplayedAndKeepsItsAttemptHistory() {
        Tenant tenant = createTenant("replay");
        createEndpoint(tenant.apiKey());
        configureReceiver(3);

        JsonNode event = publish(tenant.apiKey(), "dead-" + UUID.randomUUID());
        JsonNode deadDelivery = awaitDelivery(tenant.apiKey(), event.path("eventId").asText(), "DEAD", 3);
        String deliveryId = deadDelivery.path("id").asText();
        assertThat(get("/api/v1/deliveries/" + deliveryId + "/attempts", tenant.apiKey()).body()).hasSize(3);

        configureReceiver(0);
        assertThat(call("POST", "/api/v1/deliveries/" + deliveryId + "/replay", tenant.apiKey(), "")
                .status()).isEqualTo(202);

        JsonNode replayed = awaitDelivery(tenant.apiKey(), event.path("eventId").asText(), "SUCCEEDED", 4);
        assertThat(replayed.path("attemptCount").asInt()).isEqualTo(4);
        assertThat(get("/api/v1/deliveries/" + deliveryId + "/attempts", tenant.apiKey()).body()).hasSize(4);
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

        JsonNode ownerDeliveries = get("/api/v1/events/" + ownerEvent.path("eventId").asText() + "/deliveries", owner.apiKey()).body();
        assertThat(ownerDeliveries).hasSize(1);
        String deliveryId = ownerDeliveries.get(0).path("id").asText();

        JsonNode hiddenDeliveries = get("/api/v1/events/" + ownerEvent.path("eventId").asText() + "/deliveries", other.apiKey()).body();
        assertThat(hiddenDeliveries).isEmpty();
        assertThat(call("POST", "/api/v1/deliveries/" + deliveryId + "/replay", other.apiKey(), "")
                .status()).isEqualTo(400);
    }

    private Tenant createTenant(String prefix) {
        JsonNode body = call("POST", "/api/v1/tenants", MASTER_KEY,
                "{\"name\":\"" + prefix + "-" + UUID.randomUUID() + "\"}").body();
        return new Tenant(body.path("id").asText(), body.path("apiKey").asText());
    }

    private void createEndpoint(String apiKey) {
        Response<JsonNode> response = call("POST", "/api/v1/endpoints", apiKey,
                "{\"url\":\"http://localhost:" + port + "/dev/receiver/deliver\",\"eventTypes\":[\"order.created\"]}");
        assertThat(response.status()).isEqualTo(201);
    }

    private JsonNode publish(String apiKey, String idempotencyKey) {
        return call("POST", "/api/v1/events", apiKey,
                "{\"type\":\"order.created\",\"idempotencyKey\":\"" + idempotencyKey
                        + "\",\"payload\":{\"orderId\":\"order-123\"}}").body();
    }

    private void configureReceiver(int failuresBeforeSuccess) {
        Response<JsonNode> response = call("POST", "/dev/receiver/config", MASTER_KEY,
                "{\"failuresBeforeSuccess\":" + failuresBeforeSuccess + ",\"failureStatus\":500}");
        assertThat(response.status()).isEqualTo(200);
    }

    private JsonNode awaitDelivery(String apiKey, String eventId, String expectedStatus, int expectedAttempts) {
        var result = new java.util.concurrent.atomic.AtomicReference<JsonNode>();
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            JsonNode deliveries = get("/api/v1/events/" + eventId + "/deliveries", apiKey).body();
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
            return new Response<>(response.statusCode(), responseBody);
        } catch (Exception exception) {
            throw new IllegalStateException("HTTP integration request failed: " + method + " " + path, exception);
        }
    }

    private record Response<T>(int status, T body) {}
    private record Tenant(String id, String apiKey) {}
}
