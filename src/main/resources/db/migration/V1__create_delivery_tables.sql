CREATE TABLE tenant (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    api_key_hash CHAR(64) NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE webhook_endpoint (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    url TEXT NOT NULL,
    signing_secret TEXT NOT NULL,
    event_types JSONB NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE webhook_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    event_type VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, idempotency_key)
);

CREATE TABLE webhook_delivery (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES webhook_event(id),
    endpoint_id UUID NOT NULL REFERENCES webhook_endpoint(id),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until TIMESTAMPTZ,
    last_status_code INTEGER,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_id, endpoint_id)
);

CREATE TABLE webhook_delivery_attempt (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL REFERENCES webhook_delivery(id),
    attempt_number INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'IN_FLIGHT',
    response_code INTEGER,
    error TEXT,
    duration_ms BIGINT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    UNIQUE (delivery_id, attempt_number)
);

CREATE INDEX idx_delivery_ready ON webhook_delivery (next_attempt_at, created_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_delivery_expired_lease ON webhook_delivery (lease_until)
    WHERE status = 'IN_FLIGHT';
CREATE INDEX idx_delivery_event ON webhook_delivery (event_id, created_at DESC);
CREATE INDEX idx_endpoint_tenant ON webhook_endpoint (tenant_id, created_at DESC);
CREATE INDEX idx_endpoint_event_types ON webhook_endpoint USING GIN (event_types);
CREATE INDEX idx_event_tenant ON webhook_event (tenant_id, created_at DESC);
CREATE INDEX idx_attempt_delivery ON webhook_delivery_attempt (delivery_id, attempt_number DESC);
