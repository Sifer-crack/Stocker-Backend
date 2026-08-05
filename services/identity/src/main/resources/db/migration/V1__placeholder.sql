-- Placeholder migration for the identity schema.
-- Shows the pattern: one V*__description.sql per logical change, applied by Flyway.
-- TODO: replace with the real domain model (users, households, membership) in a later session.
CREATE TABLE IF NOT EXISTS schema_placeholder (
    id BIGINT PRIMARY KEY
);
