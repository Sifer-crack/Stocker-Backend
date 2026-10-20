-- Expiry notifications: when an item expires and how many days before expiry the user wants to be told.
-- Both nullable: an item without an expiration date never produces a notification.
ALTER TABLE pantry_items ADD COLUMN IF NOT EXISTS expiration_date DATE;
ALTER TABLE pantry_items ADD COLUMN IF NOT EXISTS notify_days_before INTEGER CHECK (notify_days_before >= 0);
CREATE INDEX IF NOT EXISTS idx_pantry_items_expiration_date ON pantry_items (expiration_date)
    WHERE expiration_date IS NOT NULL;
