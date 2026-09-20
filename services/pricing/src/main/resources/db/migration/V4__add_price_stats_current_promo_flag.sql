-- Flyway migration: V4__add_price_stats_current_promo_flag.sql
-- Service: pricing (com.stocker.pricing)
-- Tracks whether the most recent observation for a (item_id, store_id) was a promo price,
-- mirroring how current_price is already overwritten by every new observation (see
-- PriceStatsService.recordObservation). Used by SavingsCalculatorService to derive
-- discount-only savings without re-querying the raw price_records table.

ALTER TABLE price_stats
    ADD COLUMN current_promo_flag BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN price_stats.current_promo_flag IS
    'Whether the current_price observation had promo_flag=true on price_records, mirroring current_price''s "always the latest observation" semantics.';
