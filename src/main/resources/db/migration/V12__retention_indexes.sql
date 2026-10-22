-- Indexes that keep retention scans from walking live delivery history.
CREATE INDEX idx_event_outbox_published_at
    ON event_outbox (published_at)
    WHERE published_at IS NOT NULL;

CREATE INDEX idx_event_outbox_abandoned_at
    ON event_outbox (abandoned_at)
    WHERE abandoned_at IS NOT NULL;

CREATE INDEX idx_webhook_delivery_terminal_updated_at
    ON webhook_delivery (updated_at)
    WHERE status IN ('SUCCEEDED', 'DEAD');

CREATE INDEX idx_webhook_event_created_at
    ON webhook_event (created_at);
