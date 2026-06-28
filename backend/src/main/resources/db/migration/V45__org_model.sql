-- GitHub 式个人/组织权限：RAG 本地组织模型 + 收尾去租户（删 knowledge_bases.tenant_id）。
-- 组织 = 拥有成员的 owner 空间；知识库归属个人(owner_user_id)或组织(org_id)，二选一。

CREATE TABLE IF NOT EXISTS organizations (
    id                  BIGSERIAL PRIMARY KEY,
    slug                VARCHAR(64)  NOT NULL UNIQUE,   -- 短标识，如 rblk
    name                VARCHAR(128) NOT NULL,          -- 显示名，如 广州日不落科技有限公司
    created_by_user_id  BIGINT       NOT NULL,          -- 网关 auth_user_id
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS org_members (
    id          BIGSERIAL PRIMARY KEY,
    org_id      BIGINT      NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id     BIGINT      NOT NULL,                   -- 网关 auth_user_id
    role        VARCHAR(16) NOT NULL,                   -- OWNER | ADMIN | MEMBER
    created_at  TIMESTAMP   NOT NULL DEFAULT now(),
    UNIQUE (org_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_org_members_user ON org_members(user_id);
CREATE INDEX IF NOT EXISTS idx_org_members_org  ON org_members(org_id);

-- 知识库归属组织（可空；空=个人库，非空=组织库）
ALTER TABLE knowledge_bases
    ADD COLUMN IF NOT EXISTS org_id BIGINT NULL REFERENCES organizations(id);
CREATE INDEX IF NOT EXISTS idx_kb_org_visibility ON knowledge_bases(org_id, visibility);

-- 收尾 Phase 1：彻底移除 knowledge_bases 上的租户列（已无访问依赖）
DROP INDEX IF EXISTS idx_knowledge_bases_tenant_visibility;
ALTER TABLE knowledge_bases DROP COLUMN IF EXISTS tenant_id;
