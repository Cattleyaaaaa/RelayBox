ALTER TABLE event_outbox
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN abandoned_at TIMESTAMPTZ,
    ADD COLUMN failure_kind VARCHAR(16);

-- The publisher claims rows in dispatch order, so this index keeps the retry
-- scan from walking already published or quarantined history.
CREATE INDEX idx_event_outbox_retry
    ON event_outbox (next_attempt_at, dispatch_order)
    WHERE published_at IS NULL AND abandoned_at IS NULL;
