package dev.relaybox.delivery.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DeliveryModels {
    private DeliveryModels() {}
    public record DeliveryResponse(UUID id, UUID eventId, UUID endpointId, String status, long sequenceNumber,
                                   int attemptCount, int maxAttempts, Instant nextAttemptAt, Integer lastStatusCode,
                                   String lastError, Instant createdAt, Instant updatedAt) {}
    public record AttemptResponse(UUID id, int attemptNumber, String status, Integer responseCode, String error,
                                  Long durationMs, Instant startedAt, Instant completedAt) {}
    public record PageResponse<T>(List<T> items, int limit, int offset, boolean hasMore) {}
    public record EndpointStatusResponse(UUID id, boolean enabled, String circuitState, int consecutiveFailures,
                                         Instant circuitOpenUntil, Instant updatedAt) {}
    public record DeadLetterResponse(UUID id, String sourceQueue, String body, String headers,
                                     Instant deadLetteredAt, Instant replayedAt, int replayCount,
                                     String lastReplayError) {}
}
