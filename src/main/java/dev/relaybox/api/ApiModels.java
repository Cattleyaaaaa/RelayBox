package dev.relaybox.api;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApiModels {
    private ApiModels() {}

    public record CreateTenantRequest(@NotBlank @Size(max = 120) String name,
                                      @Min(1) @Max(10000) Integer eventRateLimitPerMinute) {}
    public record TenantResponse(UUID id, String name, String apiKey, int eventRateLimitPerMinute,
                                 Instant createdAt) {}

    public record RotateTenantApiKeyRequest(@Min(0) @Max(86400) Integer gracePeriodSeconds) {}
    public record RotateTenantApiKeyResponse(String apiKey, Instant oldKeyExpiresAt) {}

    public record CreateEndpointRequest(
            @NotBlank @Size(max = 2000) String url,
            @NotEmpty List<@NotBlank @Size(max = 160) String> eventTypes,
            @Min(1) @Max(1000) Integer circuitFailureThreshold,
            @Min(1) @Max(3600) Integer circuitOpenDurationSeconds) {}

    public record EndpointResponse(UUID id, String url, List<String> eventTypes,
                                   String signingSecret, boolean enabled, String circuitState,
                                   int consecutiveFailures, Instant circuitOpenUntil,
                                   int circuitFailureThreshold, int circuitOpenDurationSeconds,
                                   Instant createdAt, Instant updatedAt) {}

    public record SetEndpointEnabledRequest(@NotNull Boolean enabled) {}
    public record EndpointStatusResponse(UUID id, boolean enabled, String circuitState,
                                         int consecutiveFailures, Instant circuitOpenUntil,
                                         Instant updatedAt) {}

    public record PublishEventRequest(
            @NotBlank @Size(max = 160) String type,
            @Size(max = 200) String idempotencyKey,
            @NotNull JsonNode payload) {}

    public record PublishEventResponse(UUID eventId, int deliveryCount, boolean duplicate) {}

    public record DeliveryResponse(UUID id, UUID eventId, UUID endpointId, String status,
                                   long sequenceNumber, int attemptCount, int maxAttempts, Instant nextAttemptAt,
                                   Integer lastStatusCode, String lastError,
                                   Instant createdAt, Instant updatedAt) {}

    public record DeliveryAttemptResponse(UUID id, int attemptNumber, String status,
                                          Integer responseCode, String error, Long durationMs,
                                          Instant startedAt, Instant completedAt) {}

    public record PageResponse<T>(List<T> items, int limit, int offset, boolean hasMore) {}
}
