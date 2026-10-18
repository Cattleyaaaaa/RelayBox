ALTER TABLE webhook_endpoint
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
