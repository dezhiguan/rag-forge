-- V56：落地「系统组织」到 org_id=0（原"未归属 / 平台级"哨兵位），绑定超管 userId=4 为 OWNER。
--
-- 背景：model_usage_daily.org_id=0 历史上是平台级 / 未归属成本桶（如 LLM-as-Judge 评测）。
-- 把 org_id=0 落成一个真实组织后，超管切到「系统组织」即可查看这些平台级 / judge 成本，
-- 且多超管可共享同一系统组织统计。个人组织仍是各超管的私人空间。
--
-- 本迁移幂等、可重入：重复执行不产生副作用。

-- 1) 释放唯一 slug 'system-org'：
--    先删除"空的"占位组织（手工建的系统组织，0 知识库）；若占位组织已含知识库则改名让路，
--    保证既不误删有数据的组织、也不因 slug 冲突使部署失败。
DELETE FROM organizations o
 WHERE o.slug = 'system-org'
   AND o.id <> 0
   AND NOT EXISTS (
     SELECT 1 FROM knowledge_bases k WHERE k.org_id = o.id AND k.status <> 'deleted'
   );

UPDATE organizations
   SET slug = 'system-org-legacy-' || id
 WHERE slug = 'system-org' AND id <> 0;

-- 2) 落地 org 0（幂等）。id 显式为 0，不影响 BIGSERIAL 序列（新组织仍从当前最大值 +1 分配）。
INSERT INTO organizations (id, slug, name, created_by_user_id, type, created_at, updated_at)
VALUES (0, 'system-org', '系统组织', 4, 'SYSTEM', now(), now())
ON CONFLICT (id) DO UPDATE
  SET slug = 'system-org', name = '系统组织', type = 'SYSTEM';

-- 3) 绑定超管 userId=4 为系统组织 OWNER（幂等）。
INSERT INTO org_members (org_id, user_id, role, created_at)
VALUES (0, 4, 'OWNER', now())
ON CONFLICT (org_id, user_id) DO UPDATE SET role = 'OWNER';
