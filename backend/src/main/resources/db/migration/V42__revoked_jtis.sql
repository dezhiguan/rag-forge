CREATE TABLE IF NOT EXISTS revoked_jtis (
    jti VARCHAR(128) PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source VARCHAR(64) NOT NULL DEFAULT 'logout'
);

CREATE INDEX IF NOT EXISTS idx_revoked_jtis_expires_at ON revoked_jtis (expires_at);
