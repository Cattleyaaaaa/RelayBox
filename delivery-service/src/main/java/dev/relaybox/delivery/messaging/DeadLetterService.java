package dev.relaybox.delivery.messaging;

import dev.relaybox.delivery.api.DeliveryModels;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable store for messages that exhausted the ingest retry budget or could not be
 * decoded. Keeping them in PostgreSQL makes a broker-side dead letter inspectable and
 * replayable through the internal API instead of an opaque queue.
 */
@Service
public class DeadLetterService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RabbitTemplate rabbit;

    public DeadLetterService(JdbcTemplate jdbc, ObjectMapper mapper, RabbitTemplate rabbit) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.rabbit = rabbit;
    }

    @Transactional
    public void record(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        String messageId = message.getMessageProperties().getMessageId();
        String headers = encodeHeaders(message.getMessageProperties().getHeaders());
        jdbc.update("""
                INSERT INTO dead_letter_message (id, fingerprint, source_queue, body, headers)
                VALUES (?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (fingerprint) DO NOTHING
                """, UUID.randomUUID(), fingerprint(body, messageId), RabbitTopology.DEAD_QUEUE, body, headers);
    }

    public DeliveryModels.PageResponse<DeliveryModels.DeadLetterResponse> list(int limit, int offset) {
        List<DeliveryModels.DeadLetterResponse> rows = jdbc.query("""
                SELECT id, source_queue, body, headers::text AS headers, dead_lettered_at,
                       replayed_at, replay_count, last_replay_error
                FROM dead_letter_message
                ORDER BY dead_lettered_at DESC, id DESC
                LIMIT ? OFFSET ?
                """, (rs, row) -> new DeliveryModels.DeadLetterResponse(
                rs.getObject("id", UUID.class), rs.getString("source_queue"), rs.getString("body"),
                rs.getString("headers"), rs.getTimestamp("dead_lettered_at").toInstant(),
                rs.getTimestamp("replayed_at") == null ? null : rs.getTimestamp("replayed_at").toInstant(),
                rs.getInt("replay_count"), rs.getString("last_replay_error")), limit + 1, offset);
        boolean hasMore = rows.size() > limit;
        return new DeliveryModels.PageResponse<>(hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows),
                limit, offset, hasMore);
    }

    @Transactional
    public void replay(UUID id) {
        List<ReplayRow> rows = jdbc.query("""
                SELECT body, replayed_at FROM dead_letter_message WHERE id = ? FOR UPDATE
                """, (rs, row) -> new ReplayRow(rs.getString("body"),
                rs.getTimestamp("replayed_at") == null ? null : rs.getTimestamp("replayed_at").toInstant()), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("dead letter does not exist");
        if (rows.get(0).replayedAt() != null) throw new IllegalArgumentException("dead letter was already replayed");
        rabbit.convertAndSend(RabbitTopology.EXCHANGE, RabbitTopology.ROUTING_KEY, rows.get(0).body(), message -> {
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return message;
        });
        jdbc.update("""
                UPDATE dead_letter_message
                SET replayed_at = now(), replay_count = replay_count + 1, last_replay_error = NULL
                WHERE id = ?
                """, id);
    }

    public long pendingCount() {
        Long value = jdbc.queryForObject(
                "SELECT count(*) FROM dead_letter_message WHERE replayed_at IS NULL", Long.class);
        return value == null ? 0 : value;
    }

    private String encodeHeaders(Map<String, Object> headers) {
        try {
            return mapper.writeValueAsString(headers == null ? Map.of() : headers);
        } catch (JacksonException exception) {
            // Headers are diagnostic only; never lose the message because they cannot be encoded.
            return "{}";
        }
    }

    private String fingerprint(String body, String messageId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(body.getBytes(StandardCharsets.UTF_8));
            if (messageId != null) {
                digest.update((byte) 0);
                digest.update(messageId.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record ReplayRow(String body, Instant replayedAt) {}
}
