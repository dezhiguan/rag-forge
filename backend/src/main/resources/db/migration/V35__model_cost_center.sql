-- 模型 & 成本中心：模型注册表 + 日级用量汇总
-- 设计文档：docs/model-cost-center-design.md

-- ========== 表一：model_config（模型注册表，驱动 UI + 运行时解析） ==========
CREATE TABLE model_config (
  id            BIGSERIAL PRIMARY KEY,
  code          VARCHAR(64)  NOT NULL,
  display_name  VARCHAR(128) NOT NULL,
  vendor        VARCHAR(64)  NOT NULL,                 -- dashscope / deepseek / local
  purpose       VARCHAR(32)  NOT NULL,                 -- EMBEDDING/OCR/REWRITE/RERANK/ANSWER/JUDGE
  endpoint      VARCHAR(255),                          -- 展示/备注，运行时端点仍读 yml
  input_price   NUMERIC(10,4) NOT NULL DEFAULT 0,      -- 元/百万 Token
  output_price  NUMERIC(10,4) NOT NULL DEFAULT 0,      -- 元/百万 Token
  is_local      BOOLEAN       NOT NULL DEFAULT FALSE,
  enabled       BOOLEAN       NOT NULL DEFAULT TRUE,
  is_primary    BOOLEAN       NOT NULL DEFAULT TRUE,
  fallback_code VARCHAR(64),
  sort_order    INT           NOT NULL DEFAULT 0,
  created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
  updated_at    TIMESTAMP     NOT NULL DEFAULT NOW()
);

ALTER TABLE model_config
  ADD CONSTRAINT uq_model_config_code UNIQUE (code);

ALTER TABLE model_config
  ADD CONSTRAINT ck_model_config_purpose
  CHECK (purpose IN ('EMBEDDING','OCR','REWRITE','RERANK','ANSWER','JUDGE'));

-- 每个 purpose 至多一个 enabled 的 primary（保证运行时解析唯一）
CREATE UNIQUE INDEX uq_model_config_primary
  ON model_config(purpose)
  WHERE is_primary AND enabled;

-- 种子：当前真实接入的 7 个模型（价格以接入时官网为准，可后续改库）
INSERT INTO model_config
  (code, display_name, vendor, purpose, input_price, output_price, is_local, enabled, is_primary, fallback_code, sort_order)
VALUES
  ('qwen3-vl-embedding', 'qwen3-vl-embedding', 'dashscope', 'EMBEDDING', 0.70, 0.00, FALSE, TRUE,  TRUE,  NULL,           10),
  ('deepseek-v4-flash',  'deepseek-v4-flash',  'deepseek',  'JUDGE',     1.00, 2.00, FALSE, TRUE,  TRUE,  NULL,           20),
  ('qwen-turbo',         'qwen-turbo',         'dashscope', 'REWRITE',   0.30, 0.60, FALSE, TRUE,  TRUE,  NULL,           30),
  ('qwen-plus',          'qwen-plus',          'dashscope', 'ANSWER',    0.80, 2.00, FALSE, TRUE,  TRUE,  NULL,           40),
  ('jina-reranker-v3',   'jina-reranker-v3',   'local',     'RERANK',    0.00, 0.00, TRUE,  TRUE,  TRUE,  'qwen3-rerank', 50),
  ('qwen3-rerank',       'qwen3-rerank',       'dashscope', 'RERANK',    1.50, 0.00, FALSE, FALSE, FALSE, NULL,           60),
  ('qwen-vl-ocr',        'qwen-vl-ocr',        'dashscope', 'OCR',       5.00, 0.00, FALSE, FALSE, TRUE,  NULL,           70);

-- ========== 表二：model_usage_daily（日级汇总，看板主数据源） ==========
CREATE TABLE model_usage_daily (
  id               BIGSERIAL PRIMARY KEY,
  model_code       VARCHAR(64) NOT NULL,
  purpose          VARCHAR(32) NOT NULL,
  stat_date        DATE        NOT NULL,
  call_count       BIGINT      NOT NULL DEFAULT 0,
  input_tokens     BIGINT      NOT NULL DEFAULT 0,
  output_tokens    BIGINT      NOT NULL DEFAULT 0,
  cost             NUMERIC(12,4) NOT NULL DEFAULT 0,   -- 元
  success_count    BIGINT      NOT NULL DEFAULT 0,
  fail_count       BIGINT      NOT NULL DEFAULT 0,
  total_latency_ms BIGINT      NOT NULL DEFAULT 0,
  updated_at       TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- UPSERT 冲突键：同一模型同一天一行
CREATE UNIQUE INDEX uq_model_usage_daily
  ON model_usage_daily(model_code, stat_date);
CREATE INDEX idx_model_usage_daily_date ON model_usage_daily(stat_date DESC);
CREATE INDEX idx_model_usage_daily_purpose ON model_usage_daily(purpose, stat_date DESC);
