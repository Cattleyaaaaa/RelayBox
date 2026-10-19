package dev.relaybox.delivery.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeliveryEventIngestServiceTest {
    @Test
    void duplicateInboxEventDoesNotCreateEventOrDeliveryRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DeliveryEventIngestService ingest = new DeliveryEventIngestService(jdbc);
        UUID eventId = UUID.randomUUID();
        DeliveryEventMessage message = new DeliveryEventMessage(
                eventId, UUID.randomUUID(), "invoice.created", "{}", Instant.now(), List.of());
        when(jdbc.update(contains("INSERT INTO processed_delivery_event"), eq(eventId))).thenReturn(0);

        ingest.persist(message);

        verify(jdbc).update(contains("INSERT INTO processed_delivery_event"), eq(eventId));
        verify(jdbc, never()).update(contains("INSERT INTO webhook_event"), any(Object[].class));
        verify(jdbc, never()).update(contains("INSERT INTO webhook_delivery"), any(Object[].class));
        verifyNoMoreInteractions(jdbc);
    }
}
