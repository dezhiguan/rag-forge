-- 为检索日志增加发起人(user_id)，用于按用户精确统计"我的检索次数/延迟"。
-- 历史数据 user_id 为 NULL（无法回溯归属），只影响新产生的日志统计。
ALTER TABLE retrieval_logs ADD COLUMN IF NOT EXISTS user_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_retrieval_logs_user_created
    ON retrieval_logs (user_id, created_at);
