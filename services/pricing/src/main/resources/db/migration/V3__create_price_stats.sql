-- Flyway migration: V3__create_price_stats.sql
-- Service: pricing (com.stocker.pricing)
-- Derived per-(item_id, store_id) price summary: current/lowest/highest/median plus a JSONB
-- history of observations. price_records remains the raw, append-only source of truth this is
-- computed from (see PriceStatsService); this table exists so "cheapest price for X" is a direct
-- read instead of an aggregation over price_records at query time.

CREATE TABLE price_stats (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id             TEXT NOT NULL,
    store_id            TEXT NOT NULL,
    chain_id            TEXT NOT NULL,
    currency            TEXT NOT NULL DEFAULT 'NZD',
    current_price       NUMERIC(10, 2) NOT NULL,
    lowest_price        NUMERIC(10, 2) NOT NULL,
    highest_price       NUMERIC(10, 2) NOT NULL,
    median_price        NUMERIC(10, 2) NOT NULL,
    price_history       JSONB NOT NULL DEFAULT '[]'::JSONB,
    observation_count   INT NOT NULL DEFAULT 0,
    first_observed_at   TIMESTAMPTZ NOT NULL,
    last_observed_at    TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_price_stats_item_store UNIQUE (item_id, store_id),
    CONSTRAINT chk_price_stats_prices_non_negative
        CHECK (current_price >= 0 AND lowest_price >= 0 AND highest_price >= 0 AND median_price >= 0)
);

CREATE INDEX idx_price_stats_item_id
    ON price_stats (item_id);

CREATE INDEX idx_price_stats_price_history
    ON price_stats USING GIN (price_history);

COMMENT ON TABLE price_stats IS
    'Per-(item_id, store_id) price summary derived from price_records: current/lowest/highest/median price plus a JSONB history of observations.';
COMMENT ON COLUMN price_stats.price_history IS
    'Array of {"price": <amount>, "capturedAt": <ISO-8601 timestamp>} observations, oldest first, capped at a bounded size by PriceStatsService.';
