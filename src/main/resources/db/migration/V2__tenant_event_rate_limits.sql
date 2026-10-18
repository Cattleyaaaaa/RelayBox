ALTER TABLE tenant
    ADD COLUMN event_rate_limit_per_minute INTEGER NOT NULL DEFAULT 120
        CHECK (event_rate_limit_per_minute BETWEEN 1 AND 10000);

CREATE TABLE tenant_event_rate_limit (
    tenant_id UUID PRIMARY KEY REFERENCES tenant(id) ON DELETE CASCADE,
    window_started_at TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
