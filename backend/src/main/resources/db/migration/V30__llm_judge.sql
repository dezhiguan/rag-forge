CREATE TABLE judge_results (
  id                     BIGSERIAL PRIMARY KEY,
  answer_log_id          BIGINT REFERENCES answer_logs(id) ON DELETE SET NULL,
  kb_ids                 BIGINT[] NOT NULL,
  query                  TEXT NOT NULL,

  -- 评分 0.0-1.0
  faithfulness           NUMERIC(4,3),
  context_precision      NUMERIC(4,3),
  context_recall         NUMERIC(4,3),
  answer_relevance       NUMERIC(4,3),
  completeness           NUMERIC(4,3),
  citation_accuracy      NUMERIC(4,3),
  overall_score          NUMERIC(4,3),

  -- 裁判过程
  judge_model            VARCHAR(64)  NOT NULL DEFAULT 'deepseek-chat',
  judge_prompt_version   VARCHAR(16)  NOT NULL DEFAULT 'v1',
  judge_reasoning        TEXT,
  judge_raw_response     JSONB,
  judge_latency_ms       INT,
  judge_cost_cny         NUMERIC(10,4),

  -- 状态
  status                 VARCHAR(16)  NOT NULL DEFAULT 'COMPLETED',
  failure_reason         VARCHAR(256),

  -- 来源
  source                 VARCHAR(16)  NOT NULL,
  golden_question_id     BIGINT REFERENCES eval_questions(id) ON DELETE SET NULL,
  tenant_id              VARCHAR(64) NOT NULL DEFAULT 'default',

  created_at             TIMESTAMP NOT NULL DEFAULT NOW()
);

ALTER TABLE judge_results
  ADD CONSTRAINT ck_judge_status CHECK (status IN ('COMPLETED','FAILED','SKIPPED','RUNNING'));
ALTER TABLE judge_results
  ADD CONSTRAINT ck_judge_source CHECK (source IN ('PRODUCTION','GOLDEN_SET','MANUAL'));

CREATE INDEX idx_judge_results_created ON judge_results(created_at DESC);
CREATE INDEX idx_judge_results_kb ON judge_results USING GIN(kb_ids);
CREATE INDEX idx_judge_results_score ON judge_results(overall_score)
  WHERE status='COMPLETED';
CREATE INDEX idx_judge_results_source ON judge_results(source, created_at DESC);
CREATE INDEX idx_judge_results_answer_log ON judge_results(answer_log_id);

CREATE TABLE judge_metrics_daily (
  id                         BIGSERIAL PRIMARY KEY,
  date                       DATE NOT NULL,
  kb_id                      BIGINT,
  tenant_id                  VARCHAR(64) NOT NULL DEFAULT 'default',

  sample_count               INT NOT NULL DEFAULT 0,
  failed_count               INT NOT NULL DEFAULT 0,

  faithfulness_p50           NUMERIC(4,3),
  faithfulness_p95           NUMERIC(4,3),
  context_precision_p50      NUMERIC(4,3),
  context_precision_p95      NUMERIC(4,3),
  answer_relevance_p50       NUMERIC(4,3),
  answer_relevance_p95       NUMERIC(4,3),
  overall_p50                NUMERIC(4,3),
  overall_p95                NUMERIC(4,3),
  overall_mean               NUMERIC(4,3),
  overall_std                NUMERIC(4,3),

  total_cost_cny             NUMERIC(10,4) NOT NULL DEFAULT 0,
  updated_at                 TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uk_judge_metrics_daily_scope
  ON judge_metrics_daily(date, COALESCE(kb_id, -1), tenant_id);
CREATE INDEX idx_judge_metrics_daily_kb ON judge_metrics_daily(kb_id, date DESC);

CREATE TABLE judge_sampling_config (
  id              BIGSERIAL PRIMARY KEY,
  scope_type      VARCHAR(16) NOT NULL,
  scope_id        BIGINT,
  tenant_id       VARCHAR(64),
  sample_rate     NUMERIC(4,3) NOT NULL,
  enabled         BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_by      VARCHAR(128),

  CONSTRAINT ck_sampling_scope CHECK (scope_type IN ('GLOBAL','KB','TENANT')),
  CONSTRAINT ck_sampling_rate CHECK (sample_rate >= 0.0 AND sample_rate <= 1.0)
);

CREATE UNIQUE INDEX uk_sampling_global ON judge_sampling_config(scope_type)
  WHERE scope_type='GLOBAL';
CREATE UNIQUE INDEX uk_sampling_kb ON judge_sampling_config(scope_type, scope_id)
  WHERE scope_type='KB';
CREATE UNIQUE INDEX uk_sampling_tenant ON judge_sampling_config(scope_type, tenant_id)
  WHERE scope_type='TENANT';

-- 初始化全局抽样率
INSERT INTO judge_sampling_config (scope_type, sample_rate, updated_by)
VALUES ('GLOBAL', 0.01, 'system-init');

ALTER TABLE eval_questions
  ADD COLUMN IF NOT EXISTS judge_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS judge_tags VARCHAR(128)[];

CREATE INDEX IF NOT EXISTS idx_eval_questions_judge_enabled
  ON eval_questions(judge_enabled) WHERE judge_enabled=TRUE;
