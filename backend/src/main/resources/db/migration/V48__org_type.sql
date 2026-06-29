-- P0：GitHub 式 → Claude Code 式（一切皆组织）。
-- organizations 增加 type：INDIVIDUAL（个人组织，单人）/ TEAM（团队组织）。
-- 存量组织（均由用户显式创建）视为 TEAM；个人组织由应用按需懒创建（注册/首次访问）。
ALTER TABLE organizations
    ADD COLUMN IF NOT EXISTS type VARCHAR(16) NOT NULL DEFAULT 'TEAM';

-- 每个用户至多一个个人组织（INDIVIDUAL），防并发重复创建。
CREATE UNIQUE INDEX IF NOT EXISTS uq_org_individual_owner
    ON organizations(created_by_user_id)
    WHERE type = 'INDIVIDUAL';
