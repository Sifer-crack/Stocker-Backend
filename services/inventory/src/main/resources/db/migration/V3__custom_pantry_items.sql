-- Free-text (custom) pantry items: a catalog product is no longer required.
-- product_id becomes optional and the item carries its own name/category/unit.
ALTER TABLE pantry_items ALTER COLUMN product_id DROP NOT NULL;
ALTER TABLE pantry_items ADD COLUMN IF NOT EXISTS name     VARCHAR(255);
ALTER TABLE pantry_items ADD COLUMN IF NOT EXISTS category VARCHAR(100);
ALTER TABLE pantry_items ADD COLUMN IF NOT EXISTS unit     VARCHAR(50);
