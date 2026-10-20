-- Flyway migration: V3__create_shopping_list_items.sql
-- Service: shopping (com.stocker.shopping)
-- A user's shopping-list items plus the price comparison computed for each one by the pricing
-- service. Adding an item never waits on pricing: the row is written first with
-- comparison_status = 'PENDING' and filled in later - either from the synchronous
-- CompareItemPrices gRPC response or by backfilling from PriceRecordCaptured events.

CREATE TABLE shopping_list_items (
    id                   UUID PRIMARY KEY,
    user_id              TEXT NOT NULL,
    household_id         TEXT,
    name                 TEXT NOT NULL,
    sku                  TEXT,
    category             TEXT,
    region               TEXT,
    quantity             INT NOT NULL DEFAULT 1,
    comparison_status    TEXT NOT NULL DEFAULT 'PENDING',
    cheapest_chain_id    TEXT,
    cheapest_store_id    TEXT,
    cheapest_price       NUMERIC(10, 2),
    currency             TEXT,
    cheapest_promo_flag  BOOLEAN NOT NULL DEFAULT FALSE,
    compared_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_shopping_list_items_status
        CHECK (comparison_status IN ('PENDING', 'AVAILABLE', 'UNAVAILABLE')),
    CONSTRAINT chk_shopping_list_items_quantity CHECK (quantity > 0),
    CONSTRAINT chk_shopping_list_items_price CHECK (cheapest_price IS NULL OR cheapest_price >= 0)
);

CREATE INDEX idx_shopping_list_items_user
    ON shopping_list_items (user_id, created_at DESC);

-- One row per (item, chain, store): the full list of compared prices, not just the winner.
CREATE TABLE shopping_item_prices (
    id            BIGSERIAL PRIMARY KEY,
    item_id       UUID NOT NULL REFERENCES shopping_list_items (id) ON DELETE CASCADE,
    chain_id      TEXT NOT NULL,
    store_id      TEXT NOT NULL,
    price_amount  NUMERIC(10, 2) NOT NULL,
    currency      TEXT NOT NULL DEFAULT 'NZD',
    promo_flag    BOOLEAN NOT NULL DEFAULT FALSE,
    captured_at   TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_shopping_item_prices UNIQUE (item_id, chain_id, store_id),
    CONSTRAINT chk_shopping_item_prices_amount CHECK (price_amount >= 0)
);

COMMENT ON COLUMN shopping_list_items.comparison_status IS
    'PENDING = no comparison yet (initial state, or pricing was slow/unreachable and a backfill is awaited); AVAILABLE = comparison stored; UNAVAILABLE = pricing rejected the request (e.g. outside the service area).';
COMMENT ON TABLE shopping_item_prices IS
    'Every compared (chain, store) price for a shopping-list item; shopping_list_items.cheapest_* is the minimum of these.';
