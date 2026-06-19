ALTER TABLE api_keys
    ADD COLUMN IF NOT EXISTS principal_type VARCHAR(32) NOT NULL DEFAULT 'service',
    ADD COLUMN IF NOT EXISTS principal_id VARCHAR(128) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS scopes JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS allowed_kb_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX IF NOT EXISTS idx_api_keys_principal
    ON api_keys (principal_type, principal_id);

DELETE FROM api_keys
WHERE api_key = 'sk-ragforge-dev';
