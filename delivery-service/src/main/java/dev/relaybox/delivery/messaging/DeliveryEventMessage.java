package dev.relaybox.delivery.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DeliveryEventMessage(UUID eventId, UUID tenantId, String eventType, String payload,
                                   Instant createdAt, List<Target> targets) {
    public record Target(UUID endpointId, String url, String signingSecret, int maxAttempts,
                         int circuitFailureThreshold, int circuitOpenDurationSeconds) {}
}
