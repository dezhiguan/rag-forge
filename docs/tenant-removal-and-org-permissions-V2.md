# 去租户 + GitHub 式个人/组织权限 — 设计方案 V2

> 状态：已评审通过，进入实现
> 日期：2026-06-28
> 适用仓库：`auth-gateway`、`rag-forge`、`careermate`
> **本文取代 `docs/org-model-design-V1.html`**（V1 主张"保留 `tenant_id` 当 owner 命名空间键、组织放网关"；本轮决策反转——**彻底去掉 `tenant_id`**、**组织放 RAG 本地**、KB 归属用显式 `owner_user_id` / `org_id` 列）。

---

## 1. 背景与目标

CareerMate 面向个人、没有"租户"概念；RAG 要把权限对齐 GitHub（个人账号 + 组织账号）。`tenant_id` 是早期遗留的多租户原语，经三仓库代码勘察确认：**它已不是任何访问控制的判定闸**，只剩"存储路径命名空间 + 上传 token 自校验 + 日志分析维度 + KB 上一个写而不读的列"几处弱用途。

目标：
1. **彻底移除 `tenant_id`**（网关身份、JWT claim、RAG 上下文、KB 列、日志列、前端展示）。
2. 在 RAG 引入 **GitHub 同构的"个人 + 组织"模型**，支撑"公司知识库 + 员工共享"。
3. CareerMate 保持纯个人化，仅做无害清理。
4. 不破坏现有：CareerMate→RAG 调用、个人库隔离、平台 ADMIN「破玻璃 + 审计」。

### 已定决策
- **D1 组织落 RAG 本地**：组织/成员关系建在 RAG，网关只提供 `user_id`。CareerMate / 网关对组织概念**零改动**。
- **D2 一次性两期都实现**：去 tenant（Phase 1）+ 组织模型（Phase 2）同一轮交付，代码分两批提交，每批独立可编译。

---

## 2. 现状勘察（已读代码确认）

### 2.1 tenant **不是**访问闸
| 关注点 | 结论 | 依据 |
|---|---|---|
| RAG 知识库读写鉴权 | 只看 `owner_user_id / visibility / kb_acl`，**完全不读 tenant** | `KbAccessGuard.java`（`grep -c tenant` = 0） |
| CareerMate→RAG 跨服务 | token-exchange 按 **audience/client 白名单**放行，与 tenant 无关 | `TokenExchangeService`（`AUDIENCE_NOT_ALLOWED`） |
| `knowledge_bases.tenant_id` | 列存在（V9，默认 `tn_default`，建库时 `setTenantId`），但**无人读它做判定** | `KnowledgeBaseServiceImpl:51`、`KbAccessGuard` 不引用 |
| CareerMate 后端 | 仅 `ResumeVersionEntity.tenantId` 恒为 `DEFAULT_TENANT_ID=1L`，纯摆设；隔离靠 `auth_user_id` | `docs/SECURITY_AUTH.md` |

### 2.2 tenant 的真实（弱）用途 — 要替换/清理的点
**auth-gateway**
- `auth_users.tenant_id` 列（V6 建；注册时合成 `tn_phone_{hash}`）。
- `AuthUser.tenantId` record 字段。
- `TokenIssuer` 往 JWT 写 `tenant_id` claim（access / refresh / exchange / delegated 共 4 处）。
- `TokenStatusService`、`ConsentService`、`DevAuthDataSeeder` 引用。

**rag-forge**
- `RagAuthContext.tenantId` + `JwtVerifier` 读 `tenant_id` claim。
- `AuthProxyController.userProfile` 回显 `tenantId/tenantSlug`、`MeController` 回显 `tenantId`。
- `knowledge_bases.tenant_id` 列（V9）+ `idx_knowledge_bases_tenant_visibility` 索引；`KnowledgeBase.tenantId` 实体字段；`KnowledgeBaseServiceImpl` 建库 `setTenantId`。
- `DocumentUploadApplicationServiceImpl`：**唯一较硬的用法** — (a) OSS 对象键命名空间 `tenant/kbId/uuid/file`；(b) presign→register 一致性校验 `UPLOAD_TOKEN_TENANT_FORBIDDEN`（同一用户两步 tenant 相等，**非**跨租户隔离）。
- `AnswerService` 给 `answer_logs` 写 tenant。
- 日志表 tenant_id 列：`retrieval_logs`(V11)、`answer_logs`(V28)、`judge_*`(V30) — 分析维度。

**careermate**
- `ResumeVersionEntity.tenantId` / `DEFAULT_TENANT_ID=1L`。

> **结论**：去 tenant 不影响访问控制、不影响 CareerMate 调 RAG；改动集中在"把上述弱用途替换/删除"。

---

## 3. 目标模型：GitHub 同构

三类主体：

| 主体 | 说明 | 落在哪 |
|---|---|---|
| **平台 ADMIN** | 运维超管。默认按普通用户口径，越权读他人/组织私库须「破玻璃 + 审计」（已落地，沿用） | 网关 `platform_role` |
| **个人用户** | 默认主体。建并全权管理自有库；读 public 库 | 网关 `user_id` |
| **组织(org)** | 公司空间。拥有组织库；成员按角色共享 | **RAG 本地** |

KB 归属二选一（互斥）：
- **个人库**：`owner_user_id` 非空、`org_id` 空。
- **组织库**：`org_id` 非空、`owner_user_id` 记创建者（审计用，不参与归属判定）。

可见性 `visibility` 三档：
- `PRIVATE`：个人库仅 owner；组织库仅 org `OWNER/ADMIN`。
- `ORG`：组织库 — 该组织全体成员可读（**新增档**）。
- `PUBLIC`：全平台只读。

---

## 4. Phase 1 — 移除 tenant_id

### 4.1 auth-gateway
- 迁移 `V{n}__drop_tenant_id.sql`：`DROP INDEX idx_auth_users_tenant; ALTER TABLE auth_users DROP COLUMN tenant_id;`（`auth_sessions.target_audience` 与 tenant 无关，保留）。
- `AuthUser`：去 `tenantId` 字段。
- `AuthUserRepository`：删合成 `tn_phone_*`；所有 SELECT/INSERT 去 `tenant_id`；`mapRow` 去 `rs.getString("tenant_id")`。
- `TokenIssuer`：删 4 处 `.claim("tenant_id", ...)`。
- `TokenStatusService` / `ConsentService` / `DevAuthDataSeeder`：去 tenant 引用。

### 4.2 rag-forge
- `RagAuthContext`：去 `tenantId`（record 改签名，全引用点同步）。
- `JwtVerifier.toContext`：不再读 `claims.string("tenant_id")`。
- `AuthProxyController.userProfile`：去 `tenantId/tenantSlug`；`MeController`：去 `tenantId`。
- 迁移 `V{n}__drop_kb_tenant.sql`：`DROP INDEX idx_knowledge_bases_tenant_visibility; ALTER TABLE knowledge_bases DROP COLUMN tenant_id;`
- `KnowledgeBase` 实体去 `tenantId`；`KnowledgeBaseServiceImpl` 去 `setTenantId`。
- `DocumentUploadApplicationServiceImpl`：存储键改 `kbId/uuid/filename`（删 `currentTenantId()` 与 tenant 段）；`UPLOAD_TOKEN_TENANT_FORBIDDEN` 改为**校验 kbId 一致**（presign 已 `canWrite`，此处仅防 token 串库），payload 去 tenant 字段。
- `AnswerService` / `AdminE2eJudgeController`：日志 tenant 维度**先停写、给默认 `''`**；列保留，删列并入 Phase 2 一次性迁移（避免一次动太多表）。
- 前端 `components/UserMenu.vue` 等：不再渲染 tenantSlug。

### 4.3 careermate
- `ResumeVersionEntity.tenantId` / `DEFAULT_TENANT_ID`：低优先，可保留（恒值无害）或顺手移除。

### 4.4 兼容性与灰度
JWT 去掉 `tenant_id` claim 后旧 token 仍可用（RAG 不再读它，`null` 无人 care）。发布顺序：**① RAG/网关先发"停读"版本 → ② 网关发"停发 claim"版本 → ③ 删列迁移**。三步任意相邻版本兼容，可灰度。

---

## 5. Phase 2 — GitHub 式个人/组织权限（RAG 本地）

### 5.1 数据模型（RAG 新迁移）
```sql
CREATE TABLE organizations (
  id              BIGSERIAL PRIMARY KEY,
  slug            VARCHAR(64)  UNIQUE NOT NULL,   -- 短标识，如 rblk
  name            VARCHAR(128) NOT NULL,          -- 广州日不落科技有限公司
  created_by_user_id BIGINT    NOT NULL,
  created_at      TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE org_members (
  id        BIGSERIAL PRIMARY KEY,
  org_id    BIGINT NOT NULL REFERENCES organizations(id),
  user_id   BIGINT NOT NULL,                      -- 网关 auth_user_id
  role      VARCHAR(16) NOT NULL,                 -- OWNER | ADMIN | MEMBER
  created_at TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (org_id, user_id)
);
CREATE INDEX idx_org_members_user ON org_members(user_id);

ALTER TABLE knowledge_bases
  ADD COLUMN org_id BIGINT NULL REFERENCES organizations(id);
CREATE INDEX idx_kb_org_visibility ON knowledge_bases(org_id, visibility);
-- 同一迁移里收尾 Phase 1：DROP COLUMN tenant_id（见 4.2）
```
角色语义：`OWNER` = 建组织者/可转让，管成员 + 管所有组织库；`ADMIN` = 管组织库（建/写/删）+ 管成员（不可删组织）；`MEMBER` = 读 `ORG` 库、按 ACL 协作。

### 5.2 KbAccessGuard 组织分支（精确插入点）
现有判定保持，在 owner/public 之后、acl 之前插入组织分支。新增依赖 `OrgMemberMapper`（仿 `kbAclMapper`）。

- `canRead(kbId)`（第 48 行 `isOwner || isPublic` 之后）：
  ```
  if (kb.getOrgId() != null && "ORG".equalsIgnoreCase(kb.getVisibility())
        && orgMemberMapper.isMember(kb.getOrgId(), context.userId())) return true;
  if (kb.getOrgId() != null && orgMemberMapper.isOrgAdmin(kb.getOrgId(), context.userId())) return true; // PRIVATE 组织库 admin 可读
  ```
- `canWrite(kbId)` / `canAdmin(kbId)`（`isOwner` 之后）：
  ```
  if (kb.getOrgId() != null && orgMemberMapper.isOrgAdmin(kb.getOrgId(), context.userId())) return true; // OWNER/ADMIN
  ```
- `allReadableKbIds()` 默认分支（第 142-145 行）：并入"我所属组织的 `ORG`/admin 可见组织库" `orgReadableKbIds(context.userId())`。
- 平台 ADMIN 破玻璃分支（`isNonSystemKb`）、SERVICE_ACCOUNT、SYSTEM 排除：**不变**。

### 5.3 角色矩阵
| 能力 | 个人用户 | org MEMBER | org ADMIN | org OWNER | 平台 ADMIN |
|---|---|---|---|---|---|
| 读 `ORG` 组织库 | 否(除非 ACL) | ✅ | ✅ | ✅ | 默认否 / 破玻璃 |
| 读 `PRIVATE` 组织库 | 否 | 否 | ✅ | ✅ | 默认否 / 破玻璃 |
| 写组织库 / 加文档 | 否 | 否 | ✅ | ✅ | 默认否 / 破玻璃 |
| 建组织库 / 删库 | — | 否 | ✅ | ✅ | — |
| 管成员（增删改角色） | — | 否 | ✅(非 OWNER) | ✅ | — |
| 删组织 / 转让 OWNER | — | 否 | 否 | ✅ | — |
| 自有个人库 | 全权 | 各自 | 各自 | 各自 | 默认否 / 破玻璃 |

### 5.4 API（RAG，均经 JWT + 鉴权）
- `POST /api/v1/orgs`（建组织，建者成 OWNER）、`GET /api/v1/orgs`（我所属）、`GET /api/v1/orgs/{id}`。
- `GET /api/v1/orgs/{id}/members`、`POST .../members`（按 user 标识邀请）、`PATCH .../members/{userId}`（改角色）、`DELETE .../members/{userId}`。
- 建/改 KB：请求体新增 `orgId?`（归属个人或某组织）+ `visibility`（PRIVATE/ORG/PUBLIC）；后端校验当前用户对该 org 为 ADMIN/OWNER。
- KB 列表/详情 DTO 增 `orgId`、`orgName`、`myPermission`（沿用现有计算）。

### 5.5 前端（rag-forge）
- 新增「组织」管理页：组织列表 / 成员管理 / 角色调整。
- 知识库新建/编辑：归属选择（个人 / 我管理的组织）+ 可见性下拉（含 ORG 档）。
- 列表展示组织归属徽标 + "只读/可写"标签（沿用 `myPermission`）。

### 5.6 CareerMate
不动（个人化）。

---

## 6. 影响分析 / 不破坏清单
- **CareerMate→RAG**：不受影响（audience 链路，与 tenant/org 无关）。
- **个人库隔离**：不变（owner/public/acl）。
- **平台 ADMIN 破玻璃 + 审计**：沿用，不改。
- **组织能力**：纯新增，不改个人路径分支。
- **历史数据**：`tenant_id` 列删除前数据无访问依赖；现有 KB 自动落"个人库"（`owner_user_id` 已有，`org_id` 空）。

---

## 7. 验收用例
**Phase 1**
1. CareerMate 登录 → token-exchange → 调 RAG `/api/v1/search` 仍 200。
2. RAG 个人库隔离（TC-LIST / SEC-01）仍过；越权详情 403。
3. 文档上传走 `kbId/uuid/file` 路径成功；public 库写文档仍 403（SEC-09）。
4. 持有旧 token（含 `tenant_id` claim）访问 RAG 仍正常。

**Phase 2**
5. 用户 A 建组织 Org1（成 OWNER）→ 邀 B 为 MEMBER。
6. A 在 Org1 建 `ORG` 库 → B 可读、不可写（403）；非成员 C 读 403。
7. A 设 B 为 ADMIN → B 可建/写组织库、可邀人；B 不能删组织。
8. 个人库不受组织影响；平台 ADMIN 默认看不到 Org1 私库，破玻璃可见且 `admin_access_audit` 留痕。

**构建**：`auth-gateway` / `rag-forge` 后端 `mvnw -q -DskipTests package`、前端 `npm run build` 全绿。

---

## 8. 后续文档同步（实现时一并改）
- `docs/security-and-multitenancy.md`：第 2 章"租户隔离"作废，改为"个人/组织 owner 空间"。
- `docs/auth-and-permissions.md`：第 4/5 章补组织角色与 `ORG` 可见性。
- `docs/org-model-design-V1.html`：标注"已被本 V2 取代"。
- `CLAUDE.md` / `docs/architecture.md`：数据表与权限模型同步。
