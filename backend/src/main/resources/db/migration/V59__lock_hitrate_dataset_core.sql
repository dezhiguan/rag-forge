-- V59：把「命中率评测集(80题)·v1」的题目锁定为核心题（is_core=TRUE），冻结不可编辑/删除。
-- 复用 V57 的核心题冻结机制（后端 update/delete 命中 is_core 抛 CORE_QUESTION_LOCKED，前端显示 🔒）。
-- 仅置 is_core 一列，不动 question / expected_text_snippets / judge_tags 等其它字段。
-- 按数据集名精确匹配，幂等可重入；无该数据集的环境为 no-op。
UPDATE eval_questions
SET is_core = TRUE
WHERE dataset_id IN (
  SELECT id FROM eval_datasets WHERE name = '命中率评测集(80题)·v1'
);
