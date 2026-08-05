-- Outbox table for the transactional outbox pattern.
-- Domain events are written here in the SAME transaction as the domain change, then a
-- background publisher (infrastructure/outbox/OutboxPublisher) forwards them to Kafka.
-- Scaffold only: exact columns can be adjusted when the real domain model lands.
CREATE TABLE IF NOT EXISTS outbox (
    id BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(128) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    published_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox (status, published_at);
