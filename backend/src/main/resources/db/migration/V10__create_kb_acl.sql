CREATE TABLE IF NOT EXISTS kb_acl (
    id             BIGSERIAL PRIMARY KEY,
    kb_id          BIGINT       NOT NULL REFERENCES knowledge_bases (id) ON DELETE CASCADE,
    principal_type VARCHAR(32)  NOT NULL DEFAULT 'user',
    principal_id   VARCHAR(128) NOT NULL DEFAULT '0',
    permission     VARCHAR(16)  NOT NULL DEFAULT 'read',
    expires_at     TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_kb_acl_permission CHECK (permission IN ('read', 'write', 'admin')),
    CONSTRAINT ck_kb_acl_principal_type CHECK (principal_type IN ('user', 'service'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_acl_principal_kb
    ON kb_acl (kb_id, principal_type, principal_id);

CREATE INDEX IF NOT EXISTS idx_kb_acl_principal_permission
    ON kb_acl (principal_type, principal_id, permission);

INSERT INTO kb_acl (kb_id, principal_type, principal_id, permission)
SELECT kb.id, 'user', kb.owner_user_id::VARCHAR, 'admin'
FROM knowledge_bases kb
WHERE kb.owner_user_id <> 0
ON CONFLICT (kb_id, principal_type, principal_id) DO UPDATE
SET permission = 'admin',
    updated_at = CURRENT_TIMESTAMP;
