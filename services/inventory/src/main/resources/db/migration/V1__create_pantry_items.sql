-- Baseline of the pantry_items table that already exists on the shared Neon database (it was created by hand).
-- Existing databases are baselined at version 1 (spring.flyway.baseline-on-migrate) and skip this file;
-- a fresh database (local docker postgres, CI) gets the table created here.
CREATE TABLE IF NOT EXISTS pantry_items (
    pantry_item_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID        NOT NULL,
    product_id     UUID        NOT NULL,
    quantity       INTEGER     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
