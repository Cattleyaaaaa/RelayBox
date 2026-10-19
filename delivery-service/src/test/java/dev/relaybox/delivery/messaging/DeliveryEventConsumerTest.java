package dev.relaybox.delivery.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class DeliveryEventConsumerTest {
    private static final String BODY = "{}";

    @Test
    void retriesTransientDatabaseFailureThenAcknowledgesAfterPersistence() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        DeliveryEventIngestService ingest = mock(DeliveryEventIngestService.class);
        DeliveryEventMessage message = message();
        when(mapper.readValue(BODY, DeliveryEventMessage.class)).thenReturn(message);
        doThrow(new TransientDataAccessResourceException("temporary database outage"))
                .doNothing().when(ingest).persist(message);
        DeliveryEventConsumer consumer = new DeliveryEventConsumer(mapper, ingest, 3, 10);

        consumer.receive(BODY);

        verify(ingest, times(2)).persist(message);
    }

    @Test
    void propagatesLastDatabaseFailureAfterConfiguredAttempts() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        DeliveryEventIngestService ingest = mock(DeliveryEventIngestService.class);
        DeliveryEventMessage message = message();
        TransientDataAccessResourceException failure =
                new TransientDataAccessResourceException("database remains unavailable");
        when(mapper.readValue(BODY, DeliveryEventMessage.class)).thenReturn(message);
        doThrow(failure).when(ingest).persist(message);
        DeliveryEventConsumer consumer = new DeliveryEventConsumer(mapper, ingest, 3, 10);

        TransientDataAccessResourceException thrown = assertThrows(
                TransientDataAccessResourceException.class, () -> consumer.receive(BODY));

        assertSame(failure, thrown);
        verify(ingest, times(3)).persist(message);
    }

    private DeliveryEventMessage message() {
        return new DeliveryEventMessage(UUID.randomUUID(), UUID.randomUUID(), "invoice.created",
                "{}", Instant.now(), List.of());
    }
}
