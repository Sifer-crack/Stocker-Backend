-- Free-text pantry items have no catalog product, and carry their own name/category/unit.
ALTER TABLE expiration_notifications ALTER COLUMN product_id DROP NOT NULL;
ALTER TABLE expiration_notifications ADD COLUMN item_name VARCHAR(255);
ALTER TABLE expiration_notifications ADD COLUMN category  VARCHAR(100);
ALTER TABLE expiration_notifications ADD COLUMN unit      VARCHAR(50);
