package dev.relaybox.retention;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(prefix = "relaybox.retention", name = "enabled", havingValue = "true")
public class RetentionJob {
    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final RetentionCleaner cleaner;
    private final RetentionProperties properties;

    public RetentionJob(RetentionCleaner cleaner, RetentionProperties properties) {
        this.cleaner = cleaner;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${relaybox.retention.interval-ms:3600000}",
            initialDelayString = "${relaybox.retention.initial-delay-ms:60000}")
    public void run() {
        try {
            cleaner.runOnce(properties.dryRun());
        } catch (RuntimeException exception) {
            // Keep the scheduler alive; the next run retries the same windows.
            log.error("Retention run failed; the next scheduled run will retry", exception);
        }
    }
}
