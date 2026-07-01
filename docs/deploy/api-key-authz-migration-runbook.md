# API Key 授权与多租户隔离 — 迁移运行手册（批次5）

> 配套设计:`docs/dev/api-key-authz-redesign.html` v1.0（模型 Y）
> 配套代码:批次1-4（`88d05f7` / `7bb16a9` / `3aeff92` / `c18510f`）
> 适用:生产/预发部署。**本手册含生产数据变更与部署时序,务必按序执行。**

---

## 0. 为什么是"运行手册"而非自动 Flyway

创建组织、迁移知识库归属、签发并分发 CareerMate 的 API Key,依赖**各环境不同的真实 ID** 与 **密钥安全分发**(明文仅一次性返回)。这些不适合放进对所有环境自动执行的 Flyway 迁移,故以受控运行手册执行。

## 1. ⚠️ 部署时序（关键,不可颠倒）

批次3(`3aeff92`)会移除全域公开(PUBLIC),使存量 PUBLIC 库不再跨组织可读。**必须先完成本手册的数据迁移与 CareerMate 授权,再部署含批次3的后端镜像**,否则 CareerMate 会短暂读不到原公开库。

```
① 部署仅含批次1+2 的后端(additive,不改可见性)   ——可选,先让 API Key 授权能力就绪
② 执行本手册 §2-§4(建组织 + 迁库 + 发 CareerMate key + 验证)
③ 确认 CareerMate 用新 key 走通后,再部署含批次3+4 的后端(移除 PUBLIC)
④ 部署批次4 前端
⑤ §6 收尾:降级/清理残留 PUBLIC 库
```

## 2. 建「求职数据」组织（运营账号持有,非超管、非个人组织）

前置:准备一个**普通用户运营账号**(如 `ops_career`),它将作为该组织 OWNER。

```sql
-- 2.1 确认运营账号 auth_user_id（在 authdb）
--     SELECT id, username, platform_role FROM auth_users WHERE username = 'ops_career';
--     记为 :OPS_UID

-- 2.2 建 TEAM 组织（ragforge 库）
INSERT INTO organizations (slug, name, type, created_by_user_id, created_at, updated_at)
VALUES ('career-data', '求职数据', 'TEAM', :OPS_UID, NOW(), NOW())
RETURNING id;   -- 记为 :JOB_ORG_ID

-- 2.3 运营账号入组为 OWNER
INSERT INTO org_members (org_id, user_id, role, created_at)
VALUES (:JOB_ORG_ID, :OPS_UID, 'OWNER', NOW());
```

> 也可由运营账号登录前端「创建组织」完成(推荐,自动补齐成员关系),再取其 org_id。

## 3. 迁移 5 个公共参考库到求职组织 + 收敛可见性

现状(2026-07 核实):5 个求职库(岗位JD/我的简历/薪资行情/面试题/目标公司)在**超管个人组织(org 64)**、`visibility=PUBLIC`、`owner_user_id=4`。**"我的简历库"是个人数据,不迁**(见 §5)。

```sql
-- 3.1 定位待迁库（公共参考类,排除个人简历库）
SELECT id, name, visibility, owner_user_id, org_id
  FROM knowledge_bases
 WHERE name IN ('岗位 JD 库','薪资行情库','面试题库','目标公司库');
-- 记这些 id 为 :JOB_KB_IDS

-- 3.2 迁归属到求职组织 + 归属运营账号 + 收敛为 ORG(组织内可见)
UPDATE knowledge_bases
   SET org_id = :JOB_ORG_ID,
       owner_user_id = :OPS_UID,
       visibility = 'ORG',
       updated_at = NOW()
 WHERE id IN (:JOB_KB_IDS);
```

> 可见性选 `ORG`(组织内可见)即可被本组织的 ORG_ALL key 读取;若只想 owner/管理员+key 可读,用 `PRIVATE`(ORG_ALL key 同样可读,因走 org 范围解析)。

## 4. 签发 CareerMate 的 API Key（ORG_ALL,从求职组织）

**用创建接口签发**(不要手写 SQL——接口会正确生成 hash+前缀,明文仅返回一次)。

```
# 以运营账号登录、当前组织切到「求职数据」(X-Org-Id = :JOB_ORG_ID)
POST /api/v1/keys
{
  "keyName": "careermate-prod",
  "scopeMode": "ORG_ALL",       # 读求职组织全部库
  "accessLevel": "READ",        # 本期只读
  "expiresAt": null             # 或设轮换期限
}
# 响应 apiKey(明文,仅此一次)→ 安全分发给 CareerMate 后端(密钥管理/环境变量),勿入库/日志/仓库
```

CareerMate 后端把该 key 配到调用 RAGForge 的 `X-API-Key`。终端用户无需 RAGForge 账号、无需加入组织。

## 5. 个人数据（简历）— 本期不纳入

`我的简历库`等**每个终端用户私有**的数据**不迁入求职组织、不共享**(D-B 已定)。由 CareerMate 侧自管;RAGForge 只供公共参考。原「我的简历库」如属超管个人测试数据,保持在其个人组织(收敛为 PRIVATE)或按 §6 清理。

## 6. 收尾:残留 PUBLIC 库处置

批次3 部署后,除已迁走的 4 库外,若仍有 `visibility=PUBLIC` 的库:

```sql
-- 6.1 列出残留 PUBLIC 库
SELECT id, name, owner_user_id, org_id FROM knowledge_bases WHERE visibility = 'PUBLIC';

-- 6.2 逐个处置：归属组织内的 → 降 ORG/PRIVATE；无用/测试库 → 删除
UPDATE knowledge_bases SET visibility = 'PRIVATE', updated_at = NOW() WHERE id = :ID;   -- 或 'ORG'
-- 删除须先清空文档（走应用删除接口做级联更稳），或确认无文档后 DELETE。
```

## 7. 验证清单

- [ ] CareerMate 用新 key 调 `/api/v1/search`、`/answer` 能命中 JD/薪资/面试/公司库
- [ ] 该 key 调用**读不到**其它组织的库(跨组织隔离)
- [ ] 过期/禁用该 key 后调用被 401 拒绝
- [ ] 前端:个人组织建库仅"私有";团队库"私有/组织可见";无"公开"选项
- [ ] 超管全平台视图:无"创建知识库/创建组织"入口
- [ ] 组织切换:列表/菜单/按钮随当前组织角色联动
- [ ] 全站无 `visibility=PUBLIC` 残留(或均为超管认可保留项)

## 8. 回滚

- 数据:迁移前对 `knowledge_bases`(受影响行的 org_id/owner_user_id/visibility)与 `organizations`/`org_members` 做快照;异常时按快照还原。
- 代码:批次3+4 镜像回滚到批次2 镜像(PUBLIC 逻辑恢复),CareerMate 回退到旧 key(若仍有效)。

## 9. 遗留（P2,未包含在批次1-5）

- **超管违规内容治理端点**(举报 → 审核 → 下架 → 审计):设计 §7-C 的 P2 项,作为独立需求单独实现。
- **开发者中心创建 Key 高级表单**(可视化选择 scope/KB/过期):后端已支持参数,前端增强表单为后续 UI 迭代;当前默认 ORG_ALL/READ 可用。

---
编制 2026-07-02 · 配套设计 v1.0 · 以代码与生产实际为准
