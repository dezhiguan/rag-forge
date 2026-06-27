-- 统一认证改造 V1：种子一个平台公共知识库（普通用户只读可见）
-- 幂等：仅当不存在任何 PUBLIC 知识库时插入。owner_user_id=0 表示平台所有。
INSERT INTO knowledge_bases (name, description, visibility, kb_type, owner_user_id, tenant_id, status)
SELECT '平台公共知识库', '平台公开、所有注册用户只读可见的示例知识库', 'PUBLIC', 'GENERAL', 0, 'tn_platform', 'active'
WHERE NOT EXISTS (
    SELECT 1 FROM knowledge_bases WHERE visibility = 'PUBLIC'
);
