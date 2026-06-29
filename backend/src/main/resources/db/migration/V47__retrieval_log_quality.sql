-- 驾驶舱质量指标补采集：
-- 1) avg_rerank_score：仅精排策略落「该次检索结果的 rerank 分均值」，供「平均 rerank 分（仅精排）」聚合；
-- 2) status：检索成功/失败标记，失败时由 SearchController 补记一条 ERROR，供「检索成功率」聚合。
ALTER TABLE retrieval_logs ADD COLUMN avg_rerank_score NUMERIC(6, 4);
ALTER TABLE retrieval_logs ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS';

-- 历史行无错误记录，DEFAULT 'SUCCESS' 已回填；status 用于成功率分母筛选，建索引。
CREATE INDEX idx_retrieval_logs_status ON retrieval_logs(status);
