-- 开发者中心：API key 按组织归属。
-- org_id = key 所属组织（NULL=历史/平台级，将清理）；last_used_at = 最近使用时间。
ALTER TABLE api_keys
    ADD COLUMN IF NOT EXISTS org_id BIGINT;
ALTER TABLE api_keys
    ADD COLUMN IF NOT EXISTS last_used_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_api_keys_org ON api_keys(org_id);
