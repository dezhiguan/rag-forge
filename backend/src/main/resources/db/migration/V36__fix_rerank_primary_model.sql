-- 纠正 RERANK 用途的主/备模型。
-- 实际检索链路（RerankerClient → DashScope text-rerank，模型取 app.dashscope.rerank-model=qwen3-rerank）
-- 走的是 DashScope 付费 rerank，本地 jina-reranker-v3 微服务未接入 Java 检索路径。
-- V35 种子把两者主备写反了，这里更正。

-- 先降级 jina（解除其 enabled primary，避免与下一步冲突 uq_model_config_primary）
UPDATE model_config
   SET is_primary = FALSE, enabled = FALSE, fallback_code = NULL, updated_at = NOW()
 WHERE code = 'jina-reranker-v3';

-- 再将实际生效的 qwen3-rerank 设为启用主模型，备用回退到 jina
UPDATE model_config
   SET is_primary = TRUE, enabled = TRUE, fallback_code = 'jina-reranker-v3', updated_at = NOW()
 WHERE code = 'qwen3-rerank';
