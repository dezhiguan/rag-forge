-- V57：黄金题「核心题」冻结标记。平台级黄金集（Core Set）的题打上 is_core=TRUE 后
-- 禁止编辑/删除（后端守卫 + 前端锁），作为检索/应答质量的冻结基线。幂等可重入。
ALTER TABLE eval_questions
  ADD COLUMN IF NOT EXISTS is_core BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_eval_questions_is_core
  ON eval_questions(is_core) WHERE is_core = TRUE;
