package dev.relaybox.retention;

public record RetentionReport(boolean dryRun, int outboxPublished, int outboxAbandoned,
                              int attempts, int deliveries, int events, int inbox, int deadLetters) {}
