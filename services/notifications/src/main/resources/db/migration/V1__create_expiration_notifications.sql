CREATE TABLE expiration_notifications (
    id              UUID PRIMARY KEY,
    user_id         UUID        NOT NULL,
    pantry_item_id  UUID        NOT NULL,
    product_id      UUID        NOT NULL,
    expiration_date DATE        NOT NULL,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('NEW', 'ACKNOWLEDGED')),
    created_at      TIMESTAMPTZ NOT NULL,
    acknowledged_at TIMESTAMPTZ,
    -- at most one notification per item and expiration date: the scan can run as often as it likes
    CONSTRAINT uq_expiration_notifications_item_date UNIQUE (pantry_item_id, expiration_date)
);

CREATE INDEX idx_expiration_notifications_user ON expiration_notifications (user_id, created_at DESC);
