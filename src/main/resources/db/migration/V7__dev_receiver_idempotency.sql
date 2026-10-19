CREATE TABLE dev_receiver_effect (
    delivery_id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    event_id UUID NOT NULL,
    request_body JSONB NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_dev_receiver_effect_run ON dev_receiver_effect(run_id);
