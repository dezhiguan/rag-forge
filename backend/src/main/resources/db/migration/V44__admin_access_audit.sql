-- 管理员越权访问审计：记录 ADMIN 破玻璃(break-glass)等敏感访问，便于事后追溯。
CREATE TABLE IF NOT EXISTS admin_access_audit (
    id            BIGSERIAL PRIMARY KEY,
    admin_user_id BIGINT,
    action        VARCHAR(64)  NOT NULL,
    reason        VARCHAR(512),
    trace_id      VARCHAR(64),
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_admin_access_audit_admin_created
    ON admin_access_audit (admin_user_id, created_at);
