package dev.relaybox.messaging;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxDispatcherTest {
    @Test
    void nackConfirmKeepsOutboxRowUnpublishedAndRecordsFailure() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        UUID eventId = UUID.randomUUID();
        OutboxDispatcher dispatcher = new OutboxDispatcher(jdbc, rabbit, new SimpleMeterRegistry(), 40);

        when(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(714920631)", Boolean.class))
                .thenReturn(true);
        when(jdbc.query(contains("SELECT event_id, message::text AS message"),
                ArgumentMatchers.<RowMapper<OutboxDispatcher.OutboxRow>>any(), eq(40)))
                .thenReturn(List.of(new OutboxDispatcher.OutboxRow(eventId, "{\"eventId\":\"" + eventId + "\"}")));
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "broker rejected message"));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class),
                any(CorrelationData.class));

        dispatcher.publishPending();

        verify(jdbc).update(contains("attempts = attempts + 1"), eq(eventId));
        verify(jdbc).update(contains("last_error = ?"), contains("broker rejected message"), eq(eventId));
        verify(jdbc, never()).update(contains("published_at = now()"), any(Object[].class));
        verify(rabbit).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class),
                any(CorrelationData.class));
    }
}
