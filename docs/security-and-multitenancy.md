# RAGForge 安全与多租户模型

> 当前真实版本：2026-06-22  
> 本文从现有代码反查行为，不照抄设计稿。

## 1. 身份模型

RAGForge 后端统一把调用者转换为 `RagAuthContext`，再由 Spring Security、`@PreAuthorize` 和 `KbAccessGuard` 共同完成接口级和资源级鉴权。

当前有三类 principal：

| 类型 | 来源 | 典型用途 |
|---|---|---|
| JWT user | Auth Gateway 颁发的 Bearer JWT | 管理后台用户、知识库管理员、编辑者、查看者 |
| SERVICE_ACCOUNT | API Key 或 OAuth service account token | CareerMate / Claude Desktop / MCP 等外部 Agent |
| admin | JWT 中 `rag_role=ADMIN` 或本地管理账号 | 平台管理、非 SYSTEM 知识库全局管理 |

JWT 校验逻辑在 `backend/src/main/java/com/ragforge/security/JwtVerifier.java`。它会校验 RS256 签名、issuer、audience、`exp`、`nbf`，然后把 `user_id`、`tenant_id`、`rag_role`、`rag_readable_kb_ids`、`rag_writable_kb_ids`、`scope/scopes` 转成 `RagAuthContext`。

请求过滤逻辑在 `backend/src/main/java/com/ragforge/security/JwtAuthenticationFilter.java`。过滤器从 Bearer token 解析身份，并查询 Auth event 缓存判断 JWT 是否已撤销。Redis 撤销检查不可用时，当前实现会记录 warn 并继续接受签名已验证的 token，这是可用性优先的权衡。

API Key 入口在 `backend/src/main/java/com/ragforge/security/ApiKeyInterceptor.java`。它读取 `X-API-Key`，查询 `api_keys` 表，校验 enabled、过期时间和分钟级限流后，构造 `ragRole=SERVICE_ACCOUNT` 的 `RagAuthContext`。`api_keys.allowed_kb_ids` 会被解析成 readable/writable KB 集合。

## 2. 租户隔离

RAGForge 的租户隔离主要靠两层：

1. 身份上下文中的 `tenant_id`。
2. KB 级 ACL 和日志表中的 tenant/principal 审计字段。

`knowledge_bases` 持有知识库元数据。用户可见范围不是简单按 tenant 全表放开，而是通过 `KbAccessGuard` 计算可读、可写、可管理 KB 集合。`retrieval_logs` 和 `answer_logs` 都会记录 tenant 和 principal 信息；T11 的 `AnswerService.writeLog` 会写入 `tenant_id`、`principal_id`、`kb_ids`、`trace_id`、`citations_snapshot`、`guard_rail_result` 等字段，方便审计一次 Answer 调用了哪些 KB、返回了哪些引用、是否被 GuardRails 拦截。

检索链路同样会在授权过滤后执行。Search/Answer 控制器先使用 `KbAccessGuard.filterReadable` 缩小请求中的 KB 范围，再把过滤后的 KB id 传给 `RetrievalService`。如果请求的 KB 不在可读集合里，会记录 `ragforge.authz.kb_access_denied`，T12 同时提供兼容指标 `ragforge.kb_access_denied` 方便 Grafana 直接按需求聚合。

## 3. ACL 粒度

资源权限的核心类是 `backend/src/main/java/com/ragforge/security/KbAccessGuard.java`。

它提供以下实际方法：

- `canRead(Long kbId)`
- `canWrite(Long kbId)`
- `canAdmin(Long kbId)`
- `canReadDocument(Long docId)`
- `canWriteDocument(Long docId)`
- `filterReadable(Collection<Long> kbIds)`
- `allReadableKbIds()`

ACL 表查询封装在 `backend/src/main/java/com/ragforge/mapper/KbAclMapper.java`。普通 user 如果 JWT 中没有带 `rag_readable_kb_ids` 或 `rag_writable_kb_ids`，会回退查询 `kb_acl` 表。文档级权限不是单独维护一张 doc_acl，而是通过 `DocumentMapper.selectById(docId)` 找到 `kb_id`，再复用 KB 权限判断。

admin 并不是无条件访问全部内容。`KbAccessGuard` 对 admin 也会调用 `isNonSystemKb`，即默认排除 `kb_type=SYSTEM` 的知识库，避免系统级 KB 被普通后台管理动作误伤。

## 4. SERVICE_ACCOUNT 两条路径

SERVICE_ACCOUNT 有两种进入方式：

1. API Key：`ApiKeyInterceptor.contextFrom(ApiKey)` 把 `allowed_kb_ids` 转成 readable/writable 集合，`principalId` 使用 `api-key:{id}`。
2. JWT service account：`JwtVerifier.toContext` 从 JWT claims 读取 `rag_readable_kb_ids`、`rag_writable_kb_ids` 和 `scope/scopes`。

`KbAccessGuard.readableKbIds` 对 `ragRole=SERVICE_ACCOUNT` 有特殊处理：直接使用上下文中的 readable KB 集合，不再回查 `kb_acl`。这就是“scope 预授权”路径。普通 user 则可以走 JWT claims 或 `kb_acl` fallback，这就是“user 查 ACL”路径。

## 5. 审计字段

当前代码已沉淀以下审计字段：

| 字段 | 位置 | 说明 |
|---|---|---|
| `principal_id` | `retrieval_logs`, `answer_logs`, auth context | 调用主体，如 user sub 或 `api-key:{id}` |
| `tenant_id` | `knowledge_bases`, `retrieval_logs`, `answer_logs` | 租户传播和审计维度 |
| `trace_id` | response advice / logs | 单次请求追踪 |
| `citations_snapshot` | `retrieval_logs`, `answer_logs` | 当次检索或应答引用快照 |
| `guard_rail_result` | `answer_logs` | PASS / NO_CITATIONS / OUT_OF_SCOPE / PII_LEAK |
| `scope_used` | OAuth/Agent 侧规划字段 | 当前 RAGForge 侧主要通过 `RagAuthContext.scopes()` 暴露 |
| `delegated_user_id` | Agent 委托场景规划字段 | 需要由上游 token claims 继续标准化 |
| `consent_id` | OAuth consent 场景规划字段 | 需要由 Auth Gateway claims 继续标准化 |

`scope_used`、`delegated_user_id`、`consent_id` 目前不是所有日志表都强制落列；T12 文档把它们列为 V12 后审计语义的一部分，后续如果要做合规报表，应把它们从 token claims 统一落到检索和应答日志。

## 6. Auth Gateway 事件

`backend/src/main/java/com/ragforge/events/AuthEventWebhookController.java` 暴露：

- `POST /api/v1/events/session-revoked`
- `POST /api/v1/events/password-changed`

事件处理逻辑在 `AuthEventService`。它会校验 HMAC 签名、时间窗口和幂等键，并把 JTI 撤销、用户级 revoked-after 状态写入 Redis。JWT 过滤器后续会读取这些状态，阻止已撤销 session 继续访问。

## 7. CareerMate Agent 示例

给 CareerMate Agent 颁发只读 KB 16 的 service account token 时，JWT claims 至少应包含：

```json
{
  "iss": "https://auth.careermate.cn",
  "aud": "ragforge-search-api",
  "sub": "service-account:careermate-agent",
  "principal_type": "service_account",
  "tenant_id": "careermate",
  "rag_role": "SERVICE_ACCOUNT",
  "rag_readable_kb_ids": [16],
  "rag_writable_kb_ids": [],
  "scope": "kb:16:read",
  "exp": 1782076800
}
```

如果使用 API Key，则在 RAGForge `api_keys.allowed_kb_ids` 中写入：

```json
[16]
```

调用时携带：

```http
X-API-Key: <careermate-agent-key>
```

这样 Search、Answer 和 MCP 工具都会被限制在 KB 16 的授权范围内。若请求 KB 17，`KbAccessGuard.filterReadable` 会过滤掉并记录访问拒绝指标。
