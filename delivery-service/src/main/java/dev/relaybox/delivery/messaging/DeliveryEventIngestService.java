package dev.relaybox.delivery.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.UUID;

@Service
public class DeliveryEventIngestService {
    private final JdbcTemplate jdbc;

    public DeliveryEventIngestService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void persist(DeliveryEventMessage message) {
        int inserted = jdbc.update("""
                INSERT INTO processed_delivery_event (event_id) VALUES (?)
                ON CONFLICT (event_id) DO NOTHING
                """, message.eventId());
        if (inserted == 0) return;

        jdbc.update("""
                INSERT INTO webhook_event (id, tenant_id, event_type, payload, created_at)
                VALUES (?, ?, ?, ?::jsonb, ?)
                """, message.eventId(), message.tenantId(), message.eventType(), message.payload(),
                Timestamp.from(message.createdAt()));

        for (DeliveryEventMessage.Target target : message.targets()) {
            jdbc.update("""
                    INSERT INTO webhook_endpoint (id, tenant_id, url, signing_secret, circuit_failure_threshold,
                        circuit_open_duration_seconds)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET
                        circuit_failure_threshold = EXCLUDED.circuit_failure_threshold,
                        circuit_open_duration_seconds = EXCLUDED.circuit_open_duration_seconds,
                        updated_at = now()
                    """, target.endpointId(), message.tenantId(), target.url(), target.signingSecret(),
                    target.circuitFailureThreshold(), target.circuitOpenDurationSeconds());
            long sequence = jdbc.queryForObject("""
                    UPDATE webhook_endpoint SET next_delivery_sequence = next_delivery_sequence + 1, updated_at = now()
                    WHERE id = ? RETURNING next_delivery_sequence
                    """, Long.class, target.endpointId());
            jdbc.update("""
                INSERT INTO webhook_delivery (id, event_id, endpoint_id, target_url, target_signing_secret,
                    sequence_number, max_attempts)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (event_id, endpoint_id) DO NOTHING
                """, UUID.randomUUID(), message.eventId(), target.endpointId(), target.url(),
                    target.signingSecret(), sequence, target.maxAttempts());
        }
    }
}
