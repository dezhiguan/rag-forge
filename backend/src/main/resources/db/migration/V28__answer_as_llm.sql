CREATE TABLE IF NOT EXISTS answer_logs (
  id BIGSERIAL PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  principal_id VARCHAR(128),
  kb_ids BIGINT[] NOT NULL,
  query TEXT NOT NULL,
  answer TEXT NOT NULL,
  citations_snapshot JSONB,
  retrieval_strategy VARCHAR(32),
  answer_mode VARCHAR(16),
  llm_model VARCHAR(64),
  prompt_tokens INT,
  completion_tokens INT,
  retrieval_latency_ms INT,
  llm_latency_ms INT,
  total_latency_ms INT,
  trace_id VARCHAR(128),
  guard_rail_result VARCHAR(64),
  created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_answer_logs_tenant_time
  ON answer_logs(tenant_id, created_at);

ALTER TABLE answer_logs
  ADD COLUMN IF NOT EXISTS answer_mode VARCHAR(16),
  ADD COLUMN IF NOT EXISTS llm_model VARCHAR(64),
  ADD COLUMN IF NOT EXISTS guard_rail_result VARCHAR(64);

ALTER TABLE knowledge_bases
  ADD COLUMN IF NOT EXISTS answer_mode VARCHAR(16) NOT NULL DEFAULT 'OFF',
  ADD COLUMN IF NOT EXISTS answer_model VARCHAR(64) DEFAULT 'qwen-plus',
  ADD COLUMN IF NOT EXISTS prompt_template_id BIGINT;

ALTER TABLE knowledge_bases
  DROP CONSTRAINT IF EXISTS ck_kb_answer_mode;

ALTER TABLE knowledge_bases
  ADD CONSTRAINT ck_kb_answer_mode CHECK (answer_mode IN ('OFF', 'PREVIEW', 'ON'));
