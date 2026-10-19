CREATE INDEX idx_delivery_pending_created_at
    ON webhook_delivery (created_at)
    WHERE status = 'PENDING';
