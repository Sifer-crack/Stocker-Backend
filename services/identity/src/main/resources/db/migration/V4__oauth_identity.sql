ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE users ADD COLUMN IF NOT EXISTS oauth_provider VARCHAR(32);
ALTER TABLE users ADD COLUMN IF NOT EXISTS oauth_provider_id VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(512);

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_oauth_provider
    ON users (oauth_provider, oauth_provider_id)
    WHERE oauth_provider IS NOT NULL;
