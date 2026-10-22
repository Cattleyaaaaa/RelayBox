-- Indexes that keep retention scans from walking live delivery history.
CREATE INDEX idx_webhook_delivery_terminal_updated_at
    ON webhook_delivery (updated_at)
    WHERE status IN ('SUCCEEDED', 'DEAD');

CREATE INDEX idx_webhook_event_created_at
    ON webhook_event (created_at);

CREATE INDEX idx_inbox_processed_at
    ON processed_delivery_event (processed_at);

CREATE INDEX idx_dead_letter_replayed_at
    ON dead_letter_message (replayed_at)
    WHERE replayed_at IS NOT NULL;
