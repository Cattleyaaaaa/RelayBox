ALTER TABLE webhook_endpoint
    ADD COLUMN circuit_state VARCHAR(16) NOT NULL DEFAULT 'CLOSED'
        CHECK (circuit_state IN ('CLOSED', 'OPEN', 'HALF_OPEN')),
    ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0
        CHECK (consecutive_failures >= 0),
    ADD COLUMN circuit_failure_threshold INTEGER NOT NULL DEFAULT 5
        CHECK (circuit_failure_threshold BETWEEN 1 AND 1000),
    ADD COLUMN circuit_open_duration_seconds INTEGER NOT NULL DEFAULT 30
        CHECK (circuit_open_duration_seconds BETWEEN 1 AND 3600),
    ADD COLUMN circuit_open_until TIMESTAMPTZ,
    ADD COLUMN circuit_probe_lease_until TIMESTAMPTZ;

CREATE INDEX idx_endpoint_circuit_probe
    ON webhook_endpoint (circuit_state, circuit_open_until, circuit_probe_lease_until)
    WHERE enabled = TRUE;
