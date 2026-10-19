ALTER TABLE webhook_endpoint
    ADD COLUMN next_delivery_sequence BIGINT NOT NULL DEFAULT 0
        CHECK (next_delivery_sequence >= 0);

ALTER TABLE webhook_delivery
    ADD COLUMN sequence_number BIGINT;

WITH ranked AS (
    SELECT id, row_number() OVER (PARTITION BY endpoint_id ORDER BY created_at, id) AS sequence_number
    FROM webhook_delivery
)
UPDATE webhook_delivery delivery
SET sequence_number = ranked.sequence_number
FROM ranked
WHERE delivery.id = ranked.id;

ALTER TABLE webhook_delivery
    ALTER COLUMN sequence_number SET NOT NULL,
    ADD CONSTRAINT ck_delivery_sequence_positive CHECK (sequence_number > 0);

UPDATE webhook_endpoint endpoint
SET next_delivery_sequence = COALESCE((
    SELECT max(delivery.sequence_number)
    FROM webhook_delivery delivery
    WHERE delivery.endpoint_id = endpoint.id
), 0);

CREATE UNIQUE INDEX uq_delivery_endpoint_sequence
    ON webhook_delivery (endpoint_id, sequence_number);

CREATE INDEX idx_delivery_endpoint_order
    ON webhook_delivery (endpoint_id, sequence_number, status);
