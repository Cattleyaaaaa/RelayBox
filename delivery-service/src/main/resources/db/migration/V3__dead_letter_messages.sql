CREATE TABLE dead_letter_message (
    id UUID PRIMARY KEY,
    fingerprint CHAR(64) NOT NULL UNIQUE,
    source_queue VARCHAR(160) NOT NULL,
    body TEXT NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    dead_lettered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    replayed_at TIMESTAMPTZ,
    replay_count INTEGER NOT NULL DEFAULT 0,
    last_replay_error TEXT
);

CREATE INDEX idx_dead_letter_dead_lettered_at
    ON dead_letter_message (dead_lettered_at DESC);
