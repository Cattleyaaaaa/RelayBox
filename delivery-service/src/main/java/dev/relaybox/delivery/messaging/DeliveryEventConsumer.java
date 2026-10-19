package dev.relaybox.delivery.messaging;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class DeliveryEventConsumer {
    private final ObjectMapper mapper;
    private final DeliveryEventIngestService ingestService;
    private final int maxAttempts;
    private final long initialBackoffMillis;

    public DeliveryEventConsumer(ObjectMapper mapper, DeliveryEventIngestService ingestService,
                                 @Value("${relaybox.messaging.ingest-retry-attempts:5}") int maxAttempts,
                                 @Value("${relaybox.messaging.ingest-retry-backoff-ms:100}") long initialBackoffMillis) {
        this.mapper = mapper;
        this.ingestService = ingestService;
        this.maxAttempts = Math.max(1, Math.min(10, maxAttempts));
        this.initialBackoffMillis = Math.max(10, Math.min(5000, initialBackoffMillis));
    }

    @RabbitListener(queues = RabbitTopology.QUEUE)
    public void receive(String body) throws Exception {
        DeliveryEventMessage message = mapper.readValue(body, DeliveryEventMessage.class);
        DataAccessException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ingestService.persist(message);
                return;
            } catch (DataAccessException failure) {
                lastFailure = failure;
                if (attempt == maxAttempts) break;
                long delay = Math.min(5000, initialBackoffMillis << Math.min(attempt - 1, 5));
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while retrying event persistence", interrupted);
                }
            }
        }
        throw lastFailure;
    }
}
