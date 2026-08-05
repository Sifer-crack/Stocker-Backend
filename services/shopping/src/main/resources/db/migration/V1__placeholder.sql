-- Placeholder migration for the shopping domain schema.
-- Shows the pattern: one V*__description.sql per logical change, applied by Flyway.
-- TODO: replace with the real domain model (lists, list items) in a later session.
CREATE TABLE IF NOT EXISTS schema_placeholder (
    id BIGINT PRIMARY KEY
);
