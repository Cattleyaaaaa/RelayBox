package dev.relaybox.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retention windows are intentionally conservative and disabled by default. Enabling
 * the job with dry-run still enabled only reports what would be deleted.
 */
@ConfigurationProperties(prefix = "relaybox.retention")
public record RetentionProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("true") boolean dryRun,
        @DefaultValue("3600000") long intervalMs,
        @DefaultValue("60000") long initialDelayMs,
        @DefaultValue("500") int batchSize,
        @DefaultValue("7") int outboxPublishedDays,
        @DefaultValue("30") int outboxAbandonedDays,
        @DefaultValue("30") int deliveryTerminalDays,
        @DefaultValue("90") int eventDays,
        @DefaultValue("30") int inboxDays,
        @DefaultValue("30") int deadLetterReplayedDays) {}
