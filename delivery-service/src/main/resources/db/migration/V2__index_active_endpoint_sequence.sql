-- Claiming the next delivery for an endpoint checks whether an earlier active
-- sequence exists. Only active rows can block the next delivery.
CREATE INDEX idx_delivery_active_endpoint_sequence
    ON webhook_delivery (endpoint_id, sequence_number)
    WHERE status IN ('PENDING', 'IN_FLIGHT');
