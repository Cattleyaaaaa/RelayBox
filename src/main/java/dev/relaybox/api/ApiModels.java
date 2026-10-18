package dev.relaybox.api;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApiModels {
    private ApiModels() {}

    public record CreateTenantRequest(@NotBlank @Size(max = 120) String name) {}
    public record TenantResponse(UUID id, String name, String apiKey, Instant createdAt) {}

    public record CreateEndpointRequest(
            @NotBlank @Size(max = 2000) String url,
            @NotEmpty List<@NotBlank @Size(max = 160) String> eventTypes) {}

    public record EndpointResponse(UUID id, String url, List<String> eventTypes,
                                   String signingSecret, Instant createdAt) {}

    public record PublishEventRequest(
            @NotBlank @Size(max = 160) String type,
            @Size(max = 200) String idempotencyKey,
            @NotNull JsonNode payload) {}

    public record PublishEventResponse(UUID eventId, int deliveryCount, boolean duplicate) {}

    public record DeliveryResponse(UUID id, UUID eventId, UUID endpointId, String status,
                                   int attemptCount, Instant nextAttemptAt,
                                   Integer lastStatusCode, String lastError,
                                   Instant createdAt, Instant updatedAt) {}

    public record DeliveryAttemptResponse(UUID id, int attemptNumber, String status,
                                          Integer responseCode, String error, Long durationMs,
                                          Instant startedAt, Instant completedAt) {}
}
