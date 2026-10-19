-- Flyway migration: V5__create_normalized_products.sql
-- Service: pricing (com.stocker.pricing)
-- Derived, normalised view of every store product the ingest has seen: one row per
-- (item_id, store_id), mapped onto the common-products list (canonical_key) with a parsed pack size
-- and a unit price (per kg / per L / per item). Like price_stats it is computed from price_records
-- by PriceStatsService.recordObservation and is never written to on its own.
-- Plain SQL only (no extensions), so it applies unchanged on Neon.

CREATE TABLE normalized_products (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id           TEXT NOT NULL,
    store_id          TEXT NOT NULL,
    chain_id          TEXT NOT NULL,
    canonical_key     TEXT,
    normalized_name   TEXT NOT NULL,
    display_name      TEXT NOT NULL,
    brand             TEXT,
    pack_quantity     NUMERIC(12, 3),
    pack_unit         TEXT,
    price_amount      NUMERIC(10, 2) NOT NULL,
    unit_price        NUMERIC(12, 4),
    currency          TEXT NOT NULL DEFAULT 'NZD',
    promo_flag        BOOLEAN NOT NULL DEFAULT FALSE,
    captured_at       TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_normalized_products_item_store UNIQUE (item_id, store_id),
    CONSTRAINT chk_normalized_products_pack_unit
        CHECK (pack_unit IS NULL OR pack_unit IN ('g', 'ml', 'each')),
    CONSTRAINT chk_normalized_products_price_non_negative CHECK (price_amount >= 0)
);

CREATE INDEX idx_normalized_products_canonical
    ON normalized_products (canonical_key, captured_at DESC)
    WHERE canonical_key IS NOT NULL;

CREATE INDEX idx_normalized_products_captured_at
    ON normalized_products (captured_at);

-- Search terms the engine could not map onto anything it has stored. Feeds the next version of
-- common-products.txt and the (rate-limited, user-approved) scrape plan; never triggers a scrape.
CREATE TABLE unmatched_search_terms (
    normalized_term   TEXT PRIMARY KEY,
    hit_count         INT NOT NULL DEFAULT 1,
    first_seen_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The price map: per common product and chain, how many products, the cheapest shelf price, the
-- cheapest unit price and the freshest observation.
CREATE VIEW common_product_price_map AS
SELECT canonical_key,
       chain_id,
       count(*)           AS product_count,
       min(price_amount)  AS min_price,
       min(unit_price)    AS min_unit_price,
       max(captured_at)   AS last_captured_at
FROM normalized_products
WHERE canonical_key IS NOT NULL
GROUP BY canonical_key, chain_id;

COMMENT ON TABLE normalized_products IS
    'Normalised store products derived from price_records: canonical key, parsed pack size, unit price.';
COMMENT ON TABLE unmatched_search_terms IS
    'Search terms with no stored match; input for extending common-products.txt.';
