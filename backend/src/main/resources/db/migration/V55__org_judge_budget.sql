-- 每组织 LLM-as-Judge 月度评测预算（未配置则回退平台默认 ragforge.judge.cost-guard.monthly-budget-cny）。
-- 用于抽屉「本月评测配额」按组织展示与实际拦截：本月已用达预算后，组织级黄金集回放被后端拒绝。
CREATE TABLE IF NOT EXISTS org_judge_budget (
    org_id             BIGINT         PRIMARY KEY,
    monthly_budget_cny NUMERIC(12, 4) NOT NULL,
    updated_at         TIMESTAMP      NOT NULL DEFAULT now()
);
