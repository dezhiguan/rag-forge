-- 成本计量按组织归属：model_usage_daily 加 org_id（0 = 未归属组织/平台级，如评测）。
-- 唯一键纳入 org_id，按 (model_code, stat_date, org_id) 累加。
ALTER TABLE model_usage_daily
    ADD COLUMN IF NOT EXISTS org_id BIGINT NOT NULL DEFAULT 0;

DROP INDEX IF EXISTS uq_model_usage_daily;
CREATE UNIQUE INDEX uq_model_usage_daily
    ON model_usage_daily(model_code, stat_date, org_id);
CREATE INDEX IF NOT EXISTS idx_model_usage_daily_org
    ON model_usage_daily(org_id, stat_date DESC);
