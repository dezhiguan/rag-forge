ALTER TABLE retrieval_logs
    ADD COLUMN IF NOT EXISTS principal_id VARCHAR(128) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS delegated_user_id BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS scope_used VARCHAR(128) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS consent_id VARCHAR(128) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(128) NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_retrieval_logs_principal
    ON retrieval_logs (principal_id, created_at);

CREATE INDEX IF NOT EXISTS idx_retrieval_logs_tenant
    ON retrieval_logs (tenant_id, created_at);

CREATE INDEX IF NOT EXISTS idx_retrieval_logs_trace
    ON retrieval_logs (trace_id);
