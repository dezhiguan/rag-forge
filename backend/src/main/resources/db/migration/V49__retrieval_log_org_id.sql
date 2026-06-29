-- 检索日志按组织归属：新增 org_id（检索发生时的当前组织上下文 X-Org-Id），
-- 供驾驶舱按组织聚合检索次数/延迟/质量。历史行 org_id 为空（将随历史清理）。
ALTER TABLE retrieval_logs
    ADD COLUMN IF NOT EXISTS org_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_retrieval_logs_org_created
    ON retrieval_logs(org_id, created_at DESC);
