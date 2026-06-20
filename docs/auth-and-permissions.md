# RAGForge 认证与权限模型

> 当前代码口径：Spring Security + Auth Gateway JWT + API Key 服务账号 + KB ACL。

## 1. 入口分类

| 入口 | 鉴权方式 | 代码位置 |
| --- | --- | --- |
| `/api/auth/**` | 公开入口，RAGForge 代理到 Auth Gateway | `AuthProxyController` |
| `/api/v1/health`、`/actuator/health` | 公开健康检查 | `SecurityConfig` |
| `/api/v1/.well-known/ragforge-admin-backend-jwks.json` | 公开 JWKS | `RagForgeClientJwksController` |
| `/api/v1/events/**` | HMAC webhook | `AuthEventWebhookController`、`AuthEventService` |
| `/api/v1/search`、`/api/v1/internal/**`、`/mcp/**`、`/sse` | Bearer JWT 或 `X-API-Key` | `SecurityConfig`、`ApiKeyInterceptor` |
| 其他 `/api/v1/**` | Bearer JWT | `JwtAuthenticationFilter` |

## 2. 登录与会话

前端调用 RAGForge 自身的 `/api/auth/*`，后端再代理到 Auth Gateway。

支持的接口：

- `POST /api/auth/login`：账号密码登录。
- `POST /api/auth/login-mobile`：手机号验证码登录。
- `POST /api/auth/sms/send`：发送短信验证码。
- `POST /api/auth/password/reset/init`：发起密码重置。
- `POST /api/auth/password/reset/verify`：校验重置验证码。
- `POST /api/auth/password/reset/confirm`：确认新密码并返回新会话。
- `POST /api/auth/refresh`：通过 HttpOnly `rf_refresh` cookie 刷新 access token。
- `POST /api/auth/logout`：退出当前会话。
- `POST /api/auth/logout-all`：校验密码后退出所有会话。
- `GET /api/auth/userinfo`：透传 Auth Gateway userinfo。

登录成功后：

- access token 返回给前端内存状态。
- refresh token 写入 HttpOnly cookie：`rf_refresh`。
- CSRF token 写入非 HttpOnly cookie：`rf_csrf`。
- 前端对非 GET/HEAD/OPTIONS 的认证代理请求自动带 `X-CSRF-Token`。

## 3. JWT 校验

后台管理 API 使用 Bearer JWT。

校验项：

- `issuer`：来自 `ragforge.auth.issuer`。
- `audience`：来自 `ragforge.auth.audiences`，默认包含 `ragforge-admin-api`、`ragforge-search-api`、`ragforge-api`。
- JWKS：来自 `ragforge.auth.jwks-url`，按 `ragforge.auth.jwks-cache-ttl-ms` 缓存。
- 时钟偏移：`ragforge.auth.clock-skew-seconds`。
- 会话撤销：`AuthEventService.isJwtRevoked` 查询 Redis。

JWT 会被转换成 `RagAuthContext`：

```text
userId
tenantId
ragRole
readableKbIds
writableKbIds
scopes
principalType
principalId
```

Spring Security authority 使用 `ROLE_<ragRole>`。

## 4. 角色与 scope

当前前端路由使用角色和 scope 双重控制：

| 页面 | 角色 | Scope |
| --- | --- | --- |
| Dashboard | `ADMIN`、`KB_EDITOR`、`KB_VIEWER` | `rag:dashboard:read` |
| Knowledge Base | `ADMIN`、`KB_EDITOR`、`KB_VIEWER` | `rag:kb:read` |
| Document Detail | `ADMIN`、`KB_EDITOR`、`KB_VIEWER` | `rag:doc:read` |
| Debug Console | `ADMIN`、`KB_EDITOR`、`KB_VIEWER` | `rag:debug:run` |
| Performance Probe | `ADMIN`、`KB_EDITOR` | `rag:eval:write` |
| Evaluation Lab | `ADMIN`、`KB_EDITOR` | `rag:eval:write` |
| API Gateway | `ADMIN` | `rag:apikey:admin` |

前端 `useAuth` 对角色有默认 scope 兜底，避免旧 token 缺少 scope 时页面完全不可用。真实授权仍以后端为准。

后端方法级权限示例：

- API Key 管理：`hasRole('ADMIN')`。
- 评测、LLM、维护外的大多数写操作：`ADMIN` 或 `KB_EDITOR`。
- 检索：`ADMIN`、`KB_EDITOR`、`KB_VIEWER`、`SERVICE_ACCOUNT`。
- 文档和知识库读写：继续调用 `@kbAccessGuard` 做资源级判断。

## 5. 知识库 ACL

知识库表通过 `V9__add_kb_owner_and_visibility.sql` 增加：

- `tenant_id`
- `owner_user_id`
- `visibility`
- `kb_type`

`V10__create_kb_acl.sql` 创建 `kb_acl`：

```text
kb_id
principal_type: user | service
principal_id
permission: read | write | admin
expires_at
```

`KbAccessGuard` 的判断规则：

- `ADMIN`：可访问非 `SYSTEM` 类型知识库。
- 普通用户读取：优先使用 JWT claims 的 `rag_readable_kb_ids`，没有时查询 `kb_acl`。
- 普通用户写入：优先使用 JWT claims 的 `rag_writable_kb_ids`，没有时查询 `kb_acl`。
- 普通用户 admin：查询 `kb_acl.permission = admin`。
- API Key 服务账号：只使用 API Key 的 `allowed_kb_ids`。
- 文档读写：先查文档所属 `kb_id`，再复用知识库读写判断。

检索请求如果没有显式传 `kbIds`，会自动限制为当前主体所有可读知识库；如果显式传了 `kbIds`，会过滤掉无权访问的 ID。

## 6. API Key 服务账号

API Key 主要用于外部系统、MCP 工具和服务间检索调用。

数据库字段来自 `V12__extend_api_keys.sql`：

```text
key_name
api_key
enabled
rate_limit
principal_type
principal_id
scopes
allowed_kb_ids
created_at
```

运行规则：

- 请求头使用 `X-API-Key`，默认 header 名由 `app.api-key.header` 配置。
- 只拦截 `/api/v1/search`、`/api/v1/internal/**`、`/mcp/**`、`/sse`。
- 有效 API Key 会安装 `SERVICE_ACCOUNT` 上下文。
- `allowed_kb_ids` 同时作为 readable 和 writable KB 范围。
- `rate_limit` 为分钟级限流，Redis key 前缀为 `ragforge:ratelimit:`。
- Redis 限流异常时 fail-open，放行业务请求并记录 warning。
- `dev` profile 下 `sk-ragforge-dev` 由 `DevApiKeyConfig` 提供，仅用于本地调试。

当前 API Key 页面已支持创建、列表、启停和删除；服务层创建的新 Key 默认 `rateLimit=100`。`allowedKbIds`、`scopes`、`principal`、`rateLimit` 的编辑能力仍建议后续补齐。

## 7. Auth Gateway 事件 webhook

RAGForge 接收 Auth Gateway 事件，用于同步撤销状态。

接口：

- `POST /api/v1/events/session-revoked`
- `POST /api/v1/events/password-changed`

安全校验：

- 签名 header：默认 `X-Auth-Event-Signature`。
- 时间戳 header：默认 `X-Auth-Event-Timestamp`。
- 签名算法：`HMAC-SHA256(secret, raw_request_body)`。
- 签名格式：支持 `sha256=<lowerHex>`，服务端会取 `=` 后面的十六进制值比较。
- 时间窗口：默认 `ragforge.auth.events.max-clock-skew=300s`。

Redis 状态：

- `ragforge:auth:event:<event_id>`：事件幂等。
- `ragforge:auth:revoked:jti:<jti>`：单个 access token 撤销。
- `ragforge:auth:revoked:user:<userKey>`：密码变更后按用户撤销早于该时间的 token。

`session.revoked` 会撤销事件内携带的 `jti` / `jtis`。`user.password.changed` 会额外写入用户级 revoked-after 时间。

## 8. 关键环境变量

```properties
RAGFORGE_AUTH_ISSUER=https://auth.careermate.cn
RAGFORGE_AUTH_AUDIENCE=ragforge-admin-api
RAGFORGE_AUTH_JWKS_URL=http://auth.careermate.cn/.well-known/jwks.json
RAGFORGE_AUTH_JWKS_CACHE_TTL_MS=3600000

RAGFORGE_AUTH_PROXY_BASE_URL=http://auth-gateway.auth-gateway.svc.cluster.local:8090
RAGFORGE_AUTH_PROXY_CLIENT_ID=ragforge-admin-backend
RAGFORGE_AUTH_PROXY_TARGET_AUDIENCE=ragforge-admin-api
RAGFORGE_AUTH_PROXY_TOKEN_ENDPOINT_AUDIENCE=https://auth.careermate.cn/oauth/token
RAGFORGE_AUTH_PROXY_CLIENT_ASSERTION_PRIVATE_KEY=<pem-private-key>
RAGFORGE_AUTH_PROXY_CLIENT_ASSERTION_KID=ragforge-admin-backend
RAGFORGE_AUTH_PROXY_PUBLIC_KEY_PEM=<pem-public-key>
RAGFORGE_AUTH_PROXY_COOKIE_SECURE=true

RAGFORGE_AUTH_EVENT_HMAC_SECRET=<same-as-auth-gateway-subscription-secret>
RAGFORGE_AUTH_EVENT_SIGNATURE_HEADER=X-Auth-Event-Signature
RAGFORGE_AUTH_EVENT_TIMESTAMP_HEADER=X-Auth-Event-Timestamp
RAGFORGE_AUTH_EVENT_IDEMPOTENCY_TTL=7d
RAGFORGE_AUTH_REVOKED_JTI_TTL=7d
RAGFORGE_AUTH_USER_REVOCATION_TTL=30d
```

生产环境必须保证 `RAGFORGE_AUTH_EVENT_HMAC_SECRET` 与 Auth Gateway 的 `event_subscriptions.hmac_secret` 完全一致，否则 webhook 会返回 401。

## 9. 排障清单

- 登录失败：先看 `/api/auth/login` 返回的 Auth Gateway 原始错误，确认 `RAGFORGE_AUTH_PROXY_BASE_URL`。
- 刷新失败：确认浏览器是否带 `rf_refresh` cookie，生产 HTTPS 下 `RAGFORGE_AUTH_PROXY_COOKIE_SECURE=true`。
- 后台接口 401：确认 Authorization Bearer token、issuer、audience、JWKS URL。
- 后台接口 403：确认 `rag_role`、`scopes` 和对应 `kb_acl`。
- 搜索接口 API Key 401：确认 `X-API-Key` 是否存在、`enabled=true`、当前 profile 是否为 `dev`。
- 搜索结果为空：确认主体是否有可读知识库，API Key 是否配置 `allowed_kb_ids`。
- webhook 401：确认 HMAC secret、签名 header、时间戳和服务器时间。
- 撤销不生效：确认 Redis 连接、事件是否写入 `ragforge:auth:*` key。
