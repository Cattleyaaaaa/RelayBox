package dev.relaybox.delivery.messaging;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Moves broker dead letters into PostgreSQL. The dedicated container factory keeps
 * requeue enabled, so a database outage returns the message to the queue instead of
 * dropping the last copy of an event. A short inline retry keeps a transient outage
 * from turning into a tight requeue loop.
 */
@Component
public class DeadLetterListener {
    private static final int RECORD_ATTEMPTS = 3;

    private final DeadLetterService service;

    public DeadLetterListener(DeadLetterService service) {
        this.service = service;
    }

    @RabbitListener(queues = RabbitTopology.DEAD_QUEUE, containerFactory = "deadLetterContainerFactory")
    public void receive(Message message) {
        DataAccessException lastFailure = null;
        for (int attempt = 1; attempt <= RECORD_ATTEMPTS; attempt++) {
            try {
                service.record(message);
                return;
            } catch (DataAccessException failure) {
                lastFailure = failure;
                if (attempt == RECORD_ATTEMPTS) break;
                try {
                    Thread.sleep(200L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
            }
        }
        if (lastFailure != null) throw lastFailure;
    }
}
