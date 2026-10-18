ALTER TABLE tenant
    ADD COLUMN previous_api_key_hash CHAR(64),
    ADD COLUMN previous_api_key_expires_at TIMESTAMPTZ;

CREATE UNIQUE INDEX idx_tenant_previous_api_key_hash
    ON tenant (previous_api_key_hash)
    WHERE previous_api_key_hash IS NOT NULL;
