package dev.relaybox.service;

import dev.relaybox.api.ApiModels;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Component
public class DeliveryServiceClient {
    private final RestClient client;
    private final String internalToken;

    public DeliveryServiceClient(@Value("${relaybox.delivery-service.url:http://localhost:8081}") String baseUrl,
                                 @Value("${relaybox.delivery-service.internal-token:local-internal-key}") String internalToken) {
        var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.internalToken = internalToken;
    }

    public ApiModels.PageResponse<ApiModels.DeliveryResponse> listDeliveries(UUID tenantId, UUID eventId,
                                                                              int limit, int offset) {
        return client.get().uri("/internal/tenants/{tenantId}/events/{eventId}/deliveries?limit={limit}&offset={offset}",
                        tenantId, eventId, limit, offset)
                .header("X-RelayBox-Internal-Token", internalToken)
                .retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public ApiModels.PageResponse<ApiModels.DeliveryAttemptResponse> listAttempts(UUID tenantId, UUID deliveryId,
                                                                                    int limit, int offset) {
        return client.get().uri("/internal/tenants/{tenantId}/deliveries/{deliveryId}/attempts?limit={limit}&offset={offset}",
                        tenantId, deliveryId, limit, offset)
                .header("X-RelayBox-Internal-Token", internalToken)
                .retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public void replay(UUID tenantId, UUID deliveryId) {
        client.post().uri("/internal/tenants/{tenantId}/deliveries/{deliveryId}/replay", tenantId, deliveryId)
                .header("X-RelayBox-Internal-Token", internalToken).retrieve().toBodilessEntity();
    }

    public Map<UUID, ApiModels.EndpointStatusResponse> endpointStatuses(UUID tenantId) {
        return client.get().uri("/internal/tenants/{tenantId}/endpoint-status", tenantId)
                .header("X-RelayBox-Internal-Token", internalToken)
                .retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public QueueStats queueStats() {
        return client.get().uri("/internal/queue-stats")
                .header("X-RelayBox-Internal-Token", internalToken)
                .retrieve().body(QueueStats.class);
    }

    public record QueueStats(long pending, long inFlight, long dead, long oldestPendingAgeSeconds) {}
}
