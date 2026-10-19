CREATE TABLE event_outbox (
    event_id UUID PRIMARY KEY REFERENCES webhook_event(id),
    dispatch_order BIGSERIAL NOT NULL UNIQUE,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    message JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_event_outbox_ready ON event_outbox (created_at, event_id)
    WHERE published_at IS NULL;
