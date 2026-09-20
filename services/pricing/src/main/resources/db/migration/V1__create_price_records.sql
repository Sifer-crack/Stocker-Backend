-- Flyway migration: V1__create_price_records.sql
-- Service: pricing (com.stocker.pricing)
-- Creates the core table for ingested & normalised supermarket price records.

CREATE TABLE price_records (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id         TEXT NOT NULL,
    store_id        TEXT NOT NULL,
    chain_id        TEXT NOT NULL,
    channel         TEXT NOT NULL,
    price_amount    NUMERIC(10, 2) NOT NULL,
    currency        TEXT NOT NULL DEFAULT 'NZD',
    promo_flag      BOOLEAN NOT NULL DEFAULT FALSE,
    captured_at     TIMESTAMPTZ NOT NULL,
    raw_attributes  JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_price_records_channel
        CHECK (channel IN ('pickup', 'click_and_collect')),
    CONSTRAINT chk_price_records_price_amount_non_negative
        CHECK (price_amount >= 0)
);

-- Lookups will mostly be "current price for item X across stores"
-- and "price history for item X at store Y over time".
CREATE INDEX idx_price_records_item_id
    ON price_records (item_id);

CREATE INDEX idx_price_records_store_id
    ON price_records (store_id);

CREATE INDEX idx_price_records_item_store_captured_at
    ON price_records (item_id, store_id, captured_at DESC);

CREATE INDEX idx_price_records_captured_at
    ON price_records (captured_at);

-- Optional but useful once raw_attributes starts being queried directly
-- (e.g. filtering on a source-specific field without a dedicated column).
CREATE INDEX idx_price_records_raw_attributes
    ON price_records USING GIN (raw_attributes);

COMMENT ON TABLE price_records IS
    'Normalised price observations ingested from supermarket sources (Pak''nSave, Woolworths NZ, New World).';
COMMENT ON COLUMN price_records.raw_attributes IS
    'Untouched source payload plus any source-specific fields not promoted to typed columns.';
