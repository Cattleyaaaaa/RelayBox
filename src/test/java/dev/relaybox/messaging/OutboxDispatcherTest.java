package dev.relaybox.messaging;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxDispatcherTest {
    @Test
    void nackConfirmKeepsOutboxRowUnpublishedAndSchedulesRetry() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        UUID eventId = UUID.randomUUID();
        OutboxDispatcher dispatcher = new OutboxDispatcher(jdbc, rabbit, new SimpleMeterRegistry(), 40, 50, 200, 60_000);

        when(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(714920631)", Boolean.class))
                .thenReturn(true);
        when(jdbc.query(contains("SELECT event_id, message::text AS message, attempts"),
                ArgumentMatchers.<RowMapper<OutboxDispatcher.OutboxRow>>any(), eq(40)))
                .thenReturn(List.of(new OutboxDispatcher.OutboxRow(eventId, "{\"eventId\":\"" + eventId + "\"}", 0)));
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "broker rejected message"));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class),
                any(CorrelationData.class));

        dispatcher.publishPending();

        verify(jdbc).update(contains("attempts = attempts + 1"), eq(eventId));
        verify(jdbc).update(contains("next_attempt_at = now()"), eq("INFRASTRUCTURE"),
                contains("broker rejected message"), any(), eq(eventId));
        verify(jdbc, never()).update(contains("published_at = now()"), any(Object[].class));
        verify(jdbc, never()).update(contains("abandoned_at = now()"), any(Object[].class));
        verify(rabbit).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class),
                any(CorrelationData.class));
    }

    @Test
    void messageConversionFailureIsQuarantinedInsteadOfBlockingTheOutbox() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        UUID eventId = UUID.randomUUID();
        OutboxDispatcher dispatcher = new OutboxDispatcher(jdbc, rabbit, new SimpleMeterRegistry(), 40, 3, 200, 60_000);

        when(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(714920631)", Boolean.class))
                .thenReturn(true);
        when(jdbc.query(contains("SELECT event_id, message::text AS message, attempts"),
                ArgumentMatchers.<RowMapper<OutboxDispatcher.OutboxRow>>any(), eq(40)))
                .thenReturn(List.of(new OutboxDispatcher.OutboxRow(eventId, "{}", 2)));
        doThrow(new MessageConversionException("payload cannot be converted"))
                .when(rabbit).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class),
                        any(CorrelationData.class));

        dispatcher.publishPending();

        verify(jdbc).update(contains("abandoned_at = now()"), eq("MESSAGE"),
                contains("payload cannot be converted"), eq(eventId));
        verify(jdbc, never()).update(contains("published_at = now()"), any(Object[].class));
    }
}
