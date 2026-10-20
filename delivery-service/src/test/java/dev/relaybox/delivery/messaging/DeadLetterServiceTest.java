package dev.relaybox.delivery.messaging;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeadLetterServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final DeadLetterService service = new DeadLetterService(jdbc, mock(ObjectMapper.class), rabbit);

    @Test
    void replayPublishesStoredBodyAndMarksItReplayed() {
        UUID id = UUID.randomUUID();
        when(jdbc.query(contains("SELECT body, replayed_at"),
                ArgumentMatchers.<RowMapper<DeadLetterService.ReplayRow>>any(), eq(id)))
                .thenReturn(List.of(new DeadLetterService.ReplayRow("{\"eventId\":\"e-1\"}", null)));

        service.replay(id);

        verify(rabbit).convertAndSend(eq(RabbitTopology.EXCHANGE), eq(RabbitTopology.ROUTING_KEY),
                eq("{\"eventId\":\"e-1\"}"), any(MessagePostProcessor.class));
        verify(jdbc).update(contains("replayed_at = now()"), eq(id));
    }

    @Test
    void replayRejectsAlreadyReplayedDeadLetter() {
        UUID id = UUID.randomUUID();
        when(jdbc.query(contains("SELECT body, replayed_at"),
                ArgumentMatchers.<RowMapper<DeadLetterService.ReplayRow>>any(), eq(id)))
                .thenReturn(List.of(new DeadLetterService.ReplayRow("{}", Instant.now())));

        assertThatThrownBy(() -> service.replay(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already replayed");
        verifyNoInteractions(rabbit);
    }

    @Test
    void replayRejectsUnknownDeadLetter() {
        UUID id = UUID.randomUUID();
        when(jdbc.query(contains("SELECT body, replayed_at"),
                ArgumentMatchers.<RowMapper<DeadLetterService.ReplayRow>>any(), eq(id)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.replay(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");
        verifyNoInteractions(rabbit);
    }
}
