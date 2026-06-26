-- jina-reranker-v3 为停用的备用模型，列表中排到最后。
-- 原 sort_order=50（位于 qwen3-rerank=60、qwen-vl-ocr=70 之前），改为 80。
UPDATE model_config
   SET sort_order = 80, updated_at = NOW()
 WHERE code = 'jina-reranker-v3';
