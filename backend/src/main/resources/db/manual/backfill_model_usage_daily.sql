-- 运维脚本：为 model_usage_daily 写入近 30 天基线用量。
-- 用途：dev / staging / 联调环境在真实流量积累前，让成本看板有可用基线数据。
-- 不在 Flyway 自动迁移路径（classpath:db/migration），需运维手工执行：
--   psql -h <host> -U <user> -d ragforge -f backfill_model_usage_daily.sql
-- 生产环境由 ModelUsageRecorder 实时落库，请勿执行本脚本。可重复执行（先清后写）。

DELETE FROM model_usage_daily;

INSERT INTO model_usage_daily
  (model_code, purpose, stat_date, call_count, input_tokens, output_tokens, cost, success_count, fail_count, total_latency_ms)
SELECT
  m.code,
  m.purpose,
  d::date,
  cc,
  in_tok,
  out_tok,
  ROUND((in_tok * m.input_price + out_tok * m.output_price) / 1000000.0, 4),
  cc - fails,
  fails,
  cc * m.lat
FROM generate_series(CURRENT_DATE - INTERVAL '29 day', CURRENT_DATE, INTERVAL '1 day') AS d
CROSS JOIN (
  VALUES
    -- code,               purpose,     input_price, output_price, base_calls, base_in, base_out, avg_lat_ms
    ('qwen3-vl-embedding', 'EMBEDDING', 0.70, 0.00, 60000, 320000, 0,      142),
    ('deepseek-v4-flash',  'JUDGE',     1.00, 2.00, 110,   70000,  46000,  2300),
    ('qwen-turbo',         'REWRITE',   0.30, 0.60, 720,   54000,  30000,  480),
    ('qwen-plus',          'ANSWER',    0.80, 2.00, 300,   20000,  66000,  1800),
    ('jina-reranker-v3',   'RERANK',    0.00, 0.00, 720,   0,      0,      65)
) AS m(code, purpose, input_price, output_price, base_calls, base_in, base_out, lat),
LATERAL (
  -- 按日做轻微波动，使趋势曲线有起伏
  SELECT
    (m.base_calls * (0.7 + 0.6 * random()))::bigint AS cc,
    (m.base_in    * (0.7 + 0.6 * random()))::bigint AS in_tok,
    (m.base_out   * (0.7 + 0.6 * random()))::bigint AS out_tok
) v
CROSS JOIN LATERAL (
  SELECT GREATEST(0, (v.cc * 0.008 * random())::bigint) AS fails
) f;
