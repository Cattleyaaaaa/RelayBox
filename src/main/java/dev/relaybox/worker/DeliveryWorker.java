package dev.relaybox.worker;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.relaybox.service.DeliveryQueue;
import dev.relaybox.service.DeliveryQueue.DeliveryTask;
import dev.relaybox.service.WebhookService;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Component
public class DeliveryWorker {
    private final ObjectMapper mapper;
    private final WebhookService webhookService;
    private final DeliveryQueue queue;
    private final HttpClient httpClient;
    private final Executor deliveryExecutor;

    public DeliveryWorker(ObjectMapper mapper, WebhookService webhookService, DeliveryQueue queue,
                          @Qualifier("deliveryExecutor") Executor deliveryExecutor) {
        this.mapper = mapper;
        this.webhookService = webhookService;
        this.queue = queue;
        this.deliveryExecutor = deliveryExecutor;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Scheduled(fixedDelayString = "${relaybox.worker.poll-interval-ms:1000}")
    public void poll() {
        for (DeliveryTask task : queue.claimBatch()) {
            try {
                deliveryExecutor.execute(() -> deliver(task));
            } catch (RejectedExecutionException exception) {
                queue.finish(task, null, "delivery worker is at capacity; task will be retried");
            }
        }
    }

    private void deliver(DeliveryTask task) {
        Integer statusCode = null;
        String error = null;
        try {
            String body = envelope(task);
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            String signature = sign(task.signingSecret(), timestamp + "." + body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(task.url()))
                    .timeout(Duration.ofSeconds(10))
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .header("X-Webhook-Id", task.eventId().toString())
                    .header("X-Webhook-Delivery", task.deliveryId().toString())
                    .header("X-Webhook-Timestamp", timestamp)
                    .header("X-Webhook-Signature", "v1=" + signature)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) error = "receiver returned HTTP " + statusCode;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            error = "delivery interrupted";
        } catch (Exception exception) {
            error = exception.getClass().getSimpleName() + ": " + safeMessage(exception.getMessage());
        }
        queue.finish(task, statusCode, error);
    }

    private String envelope(DeliveryTask task) throws Exception {
        JsonNode payload = mapper.readTree(task.payload());
        return webhookService.serialize(new EventEnvelope(task.eventId(), task.eventType(), task.eventCreatedAt(), payload));
    }

    private String sign(String secret, String content) throws Exception {
        byte[] key = java.util.Base64.getUrlDecoder().decode(secret);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }

    private String safeMessage(String message) {
        if (message == null) return "request failed";
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    public record EventEnvelope(UUID id, String type, Instant createdAt, JsonNode data) {}
}
