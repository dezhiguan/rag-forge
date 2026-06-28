# 去租户 + GitHub 式个人/组织权限 — Playwright 测试计划 V1

> 配套实现：`docs/tenant-removal-and-org-permissions-V2.md`
> 执行：codex（Playwright，对接 `frontend/tests/e2e/unified-auth/` 现有 harness）
> 范围：① 去 tenant 回归；② 组织 CRUD + 成员；③ 组织库访问控制；④ 破玻璃/前端/边界
> 目标：本计划 20 条用例全绿，作为该特性的验收基线。

---

## 1. 环境与依赖

| 项 | 值 |
|---|---|
| RAG Web | `RAG_WEB`（默认 `http://localhost:5173`） |
| RAG API | `RAG_API`（默认 `${RAG_WEB}/api`），组织端点 `${RAG_API}/v1/orgs` |
| Gateway | `GW_BASE`（默认 `http://localhost:8090`） |
| dev 短信码 | `DEV_SMS_CODE`（默认 `123456`） |

**复用现有 fixtures**（`tests/e2e/unified-auth/fixtures/`）：
- `api.ts`：`ragLogin(account,password)`、`ragLoginMobile(phone,code)`、`asUser(token)`、`unwrapList(body)`、`expectOkJson(api,method,url,data?)`
- `ui.ts`：`loginUserForUI(page,creds)`、`loginViaPasswordUI`、`injectSession`
- `accounts.ts`：`ADMIN` / `U_RAG` / `U_OTHER` / `U_CM` / `PHONE_NEW`

**需新增的 helper（codex 落地时补到 `fixtures/org.ts`）**：
- `createOrg(api, {slug,name})` → org
- `addMember(api, orgId, {userId,role})`
- `createKb(api, {name, orgId?, visibility?})`
- `asAdminBreakGlass(token, reason)`：在 `asUser` 基础上注入头 `X-Admin-Override:'true'` + `X-Admin-Override-Reason:reason`
- `resolveUserId(account)`：登录后从 `/api/v1/me` 取 `userId`（成员管理按 userId）

## 2. 前置数据（建议放 `beforeAll` / 复用 `ensureQaAccounts`）

- 账号矩阵就绪：`ADMIN`(platform ADMIN) / `U_RAG` / `U_OTHER`（均普通 USER）/ `U_CM`（CareerMate 手机用户）。
- 每个测试用例**自建**所需组织/库（slug 用 `org_${Date.now()}` 避免唯一冲突），用例间不共享可变状态。
- 错误码断言以后端实现为准：`ORG_SLUG_INVALID / ORG_SLUG_TAKEN / ALREADY_MEMBER / ONLY_OWNER_CAN_GRANT_OWNER / ONLY_OWNER_CAN_CHANGE_OWNER / LAST_OWNER / NOT_ORG_ADMIN / NOT_ORG_MEMBER / ORG_KB_VISIBILITY_INVALID / KB_WRITE_FORBIDDEN`。

---

## 3. 用例

### Part A — 去租户回归（5）

#### TC-TNT-01 JWT 不再签发 tenant_id claim ｜API
- **前置**：`token = ragLogin(U_RAG.account, U_RAG.password)`。
- **步骤**：解码 JWT payload（`token.split('.')[1]` base64url）。
- **预期**：`payload.tenant_id === undefined`；`payload.user_id` 存在；`payload.rag_role` 存在。
- **锚点**：`auth-gateway TokenIssuer`。

#### TC-TNT-02 /api/v1/me 不返回 tenantId ｜API
- **步骤**：`asUser(token).get('/api/v1/me')`。
- **预期**：200；body **无** `tenantId`、`tenantSlug`；含 `userId / ragRole / displayName / capabilities`。

#### TC-TNT-03 CareerMate→RAG 跨服务检索不受影响 ｜API（回归红线）
- **前置**：`U_CM` 经网关登录 → token-exchange 取 `aud=ragforge-api` token（复用 `gateway.ts`）。
- **步骤**：带该 token `POST /api/v1/search`（指向一个 public 库，body 含 `query` + `kbIds`）。
- **预期**：**200**。证明去 tenant 后 audience 链路仍放行（与 tenant 无关）。

#### TC-TNT-04 个人库跨用户隔离不变 ｜API（SEC 回归）
- **前置**：`U_RAG` `createKb({name, visibility:'PRIVATE'})` → KB_R。
- **步骤**：`asUser(U_OTHER token)` ① `GET /api/v1/kb/{KB_R}`；② `GET /api/v1/kb` 列表。
- **预期**：① **403**；② 列表**不含** KB_R。

#### TC-TNT-05 上传存储键无 tenant 段 ｜API
- **前置**：`U_RAG` 拥有写权限的库 KB_R。
- **步骤**：`POST /documents/presign` → 读 `storageKey`；继续 `register`。
- **预期**：`storageKey` 匹配 `^kb_${KB_R}/[0-9a-f-]+/.+`（**无 `tn_*` 前缀**）；register 成功；对 **public 库** presign 仍 **403**（SEC-09 回归）。

---

### Part B — 组织 CRUD & 成员（6）

#### TC-ORG-01 创建组织→创建者成为 OWNER ｜API
- **步骤**：`U_RAG` `POST /api/v1/orgs {slug:'org_<ts>', name:'广州日不落科技'}` → `GET /api/v1/orgs`。
- **预期**：成功；列表含该组织且 `myRole==='OWNER'`；`slug/name` 回显正确。

#### TC-ORG-02 slug 非法格式被拒 ｜API（边界，参数化）
- **步骤**：对 `['AB', 'has_underscore', '中文名', 'a', 'UPPER']` 逐个 `POST /api/v1/orgs`。
- **预期**：全部 **400 `ORG_SLUG_INVALID`**。

#### TC-ORG-03 slug 全局唯一 ｜API（边界）
- **前置**：`U_RAG` 已建 `slug=dup_<ts>`。
- **步骤**：`U_OTHER` 再建同 `slug`。
- **预期**：**409 `ORG_SLUG_TAKEN`**。

#### TC-ORG-04 OWNER 添加成员 + 成员列表 + 防重复 ｜API
- **前置**：`U_RAG` 建组织 Org1；`U_OTHER.id = resolveUserId(U_OTHER)`。
- **步骤**：① `POST /orgs/{Org1}/members {userId:U_OTHER.id, role:'MEMBER'}`；② `GET .../members`；③ 再次添加同一 userId。
- **预期**：① 200；② 列表含 `U_OTHER`(MEMBER)；③ **409 `ALREADY_MEMBER`**。

#### TC-ORG-05 仅 OWNER 可授予/变更 OWNER ｜API（权限边界）
- **前置**：Org1 中 `U_OTHER` 角色为 `ADMIN`；存在第三成员 U3(MEMBER)。
- **步骤**：以 `U_OTHER`(ADMIN) ① `PATCH .../members/{U3} {role:'OWNER'}`；② `PATCH .../members/{ownerId} {role:'MEMBER'}`。
- **预期**：① **403 `ONLY_OWNER_CAN_GRANT_OWNER`**；② **403 `ONLY_OWNER_CAN_CHANGE_OWNER`**；OWNER 本人执行①成功。

#### TC-ORG-06 不可移除/降级最后一个 OWNER ｜API（边界）
- **前置**：Org1 仅 1 个 OWNER。
- **步骤**：① OWNER `PATCH .../members/{self} {role:'MEMBER'}`；② `DELETE .../members/{self}`；③ 追加第二个 OWNER 后再 `DELETE` 原 OWNER。
- **预期**：①② 均 **409 `LAST_OWNER`**；③ 成功（已不止一个 OWNER）。

---

### Part C — 组织库访问控制（6）

#### TC-OKB-01 org ADMIN 建 ORG 可见组织库 ｜API
- **前置**：`U_RAG` 为 Org1 的 OWNER。
- **步骤**：`POST /api/v1/kb {name, orgId:Org1, visibility:'ORG'}`。
- **预期**：200；返回库 `orgId===Org1`、`visibility==='ORG'`、`myPermission==='admin'`、`orgName` 回填。

#### TC-OKB-02 普通成员不能在组织下建库 ｜API（权限）
- **前置**：`U_OTHER` 是 Org1 的 `MEMBER`。
- **步骤**：`asUser(U_OTHER)` `POST /api/v1/kb {orgId:Org1, visibility:'ORG'}`。
- **预期**：**403 `NOT_ORG_ADMIN`**。

#### TC-OKB-03 组织库禁止 PUBLIC ｜API（边界）
- **步骤**：org ADMIN `POST /api/v1/kb {orgId:Org1, visibility:'PUBLIC'}`。
- **预期**：**400 `ORG_KB_VISIBILITY_INVALID`**。

#### TC-OKB-04 MEMBER 读 ORG 库可、写不可 ｜API（核心权限）
- **前置**：org ADMIN 建 ORG 库 KB_O；`U_OTHER` 为 MEMBER。
- **步骤**：`asUser(U_OTHER)` ① `GET /api/v1/kb/{KB_O}`；② presign 上传 KB_O。
- **预期**：① **200**（可读）；② **403 `KB_WRITE_FORBIDDEN`**。

#### TC-OKB-05 非组织成员读组织库被拒 ｜API（隔离）
- **前置**：`U_NON` 非 Org1 成员（用另一个干净账号或 `U_CM` 经 RAG 注册后）。
- **步骤**：`GET /api/v1/kb/{KB_O}` 与 `GET /api/v1/kb`。
- **预期**：详情 **403**；列表**不含** KB_O。

#### TC-OKB-06 组织 PRIVATE 库可见域分层 ｜API（权限分层）
- **前置**：org ADMIN 建 `visibility:'PRIVATE'` 组织库 KB_OP。
- **步骤**：分别以 OWNER/ADMIN、MEMBER、非成员读 `GET /kb/{KB_OP}` + 看列表。
- **预期**：OWNER/ADMIN **200 且可写**；**MEMBER 403、列表不含**（PRIVATE 仅管理者）；非成员 403。

---

### Part D — 破玻璃 / 前端 / 联动（3）

#### TC-ADM-01 平台 ADMIN 破玻璃才看组织私库 + 留审计 ｜API（安全）
- **前置**：存在不属于 ADMIN 的组织私库 KB_OP。
- **步骤**：平台 `ADMIN` ① 普通 `GET /api/v1/kb`；② `asAdminBreakGlass(token,'support-debug')` 再 `GET /api/v1/kb`；③ 校验审计（管理端查 `admin_access_audit` 或断言审计接口/日志）。
- **预期**：① 列表**不含** KB_OP（默认收口）；② 带头后**可见**；③ 审计新增一条（含 reason）。

#### TC-UI-01 组织管理页端到端 ｜UI
- **步骤**：`loginUserForUI(page, U_RAG)` → 进 `/orgs` → 「创建组织」填 slug/name → 「管理成员」加 `U_OTHER`(MEMBER) → 角色下拉改 ADMIN → 「移除」。
- **预期**：侧栏含「🏢 组织」入口；创建后列表出现新组织、角色显示「所有者」；成员增/改/删后列表实时刷新且有 Toast；最后一个 OWNER 的下拉/移除被拦截并提示（对应 `LAST_OWNER`）。

#### TC-UI-02 知识库徽标 + 建库表单联动 ｜UI
- **前置**：`U_RAG` 为某组织 ADMIN。
- **步骤**：进 `/knowledge` →「创建知识库」。
- **预期**：① 归属下拉含「个人（我自己）」+「组织：{名}」；② 选「个人」可见性=`私有/公开`，选「组织」切为`私有/组织可见`（**无公开项**）；③ 建一个 ORG 库后，列表该行库名旁出现「🏢 组织·{名}」+「组织可见」徽标；个人 public 库显示「公开」徽标。

---

## 4. 覆盖矩阵

| 维度 | 用例 |
|---|---|
| 去 tenant 回归 | TC-TNT-01/02/03/04/05 |
| 组织功能正路 | TC-ORG-01/04、TC-OKB-01、TC-UI-01 |
| 权限矩阵（成员/ADMIN/OWNER/非成员/平台ADMIN） | TC-OKB-02/04/05/06、TC-ORG-05、TC-ADM-01 |
| 边界 / 负路 | TC-ORG-02/03/06、TC-OKB-02/03 |
| 安全（隔离/破玻璃/审计） | TC-TNT-04、TC-OKB-05、TC-ADM-01 |
| 跨服务回归 | TC-TNT-03 |
| 前端 | TC-UI-01/02 |

## 5. 执行约定
- 用例**自建并清理**组织/库（slug 带时间戳），避免相互污染与唯一冲突。
- API 层断言优先用 `expectOkJson` / 显式 status + 错误码；UI 层用 `data-test` 选择器（如缺失，codex 落地时补到 `Organizations.vue` / `KnowledgeBase.vue`）。
- 全部 20 条通过即为该特性验收基线；与 `unified-auth-test-plan-V1.md` 的 Part F/G 互补，不重复。
