-- Flyway migration: V4__create_favorite_stores.sql
-- Service: shopping (com.stocker.shopping)
-- Supermarkets a user saved as favorites: a chain (newworld | paknsave | woolworths), optionally narrowed to
-- one store of it. A NULL store_id means "the whole chain".

CREATE TABLE favorite_stores (
    id          UUID PRIMARY KEY,
    user_id     TEXT        NOT NULL,
    chain_id    TEXT        NOT NULL,
    store_id    TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_favorite_stores_chain CHECK (chain_id IN ('newworld', 'paknsave', 'woolworths'))
);

-- One favorite per user + chain + store. COALESCE makes NULL store_ids collide too (a plain UNIQUE
-- constraint treats NULLs as distinct, which would allow duplicate whole-chain favorites).
CREATE UNIQUE INDEX uq_favorite_stores_user_chain_store
    ON favorite_stores (user_id, chain_id, COALESCE(store_id, ''));

CREATE INDEX idx_favorite_stores_user ON favorite_stores (user_id, created_at);
