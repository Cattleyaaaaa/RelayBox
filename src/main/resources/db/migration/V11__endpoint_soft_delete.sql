ALTER TABLE webhook_endpoint
    ADD COLUMN deleted_at TIMESTAMPTZ;

-- Listing and event fan-out only consider live endpoints.
CREATE INDEX idx_endpoint_tenant_active
    ON webhook_endpoint (tenant_id, created_at DESC)
    WHERE deleted_at IS NULL;
