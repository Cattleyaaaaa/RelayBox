package dev.relaybox.worker;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@ConditionalOnProperty(prefix = "relaybox.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WorkerConfiguration {
    @Bean(name = "deliveryExecutor")
    Executor deliveryExecutor(
            @Value("${relaybox.worker.concurrency:16}") int configuredConcurrency,
            @Value("${relaybox.worker.max-concurrency:32}") int configuredMaximumConcurrency,
            @Value("${relaybox.worker.queue-capacity:500}") int configuredQueueCapacity) {
        int coreConcurrency = Math.max(1, Math.min(256, configuredConcurrency));
        int maximumConcurrency = Math.max(coreConcurrency, Math.min(512, configuredMaximumConcurrency));
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreConcurrency);
        executor.setMaxPoolSize(maximumConcurrency);
        executor.setQueueCapacity(Math.max(1, Math.min(10000, configuredQueueCapacity)));
        executor.setThreadNamePrefix("webhook-delivery-");
        executor.initialize();
        return executor;
    }
}
