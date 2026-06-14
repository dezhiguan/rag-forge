# SkyWalking 业务日志查询（RAGForge）

RAGForge 仅将 **`com.ragforge`** 业务包日志上报 SkyWalking OAP；Spring、Tomcat、Hikari、MyBatis 等框架日志仍在控制台可见（默认 `FRAMEWORK_LOG_LEVEL=WARN`），但**不会**进入 SkyWalking Logs 视图。

配置见 `backend/src/main/resources/logback-spring.xml`。

## 环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `APP_LOG_LEVEL` | `INFO` | 业务包 `com.ragforge` |
| `FRAMEWORK_LOG_LEVEL` | `WARN` | 框架/中间件 logger |
| `SPRING_PROFILES_ACTIVE` | — | 生产需含 `prod,skywalking-log` 才启用 GRPC 上报 |

## UI 查询建议

1. **按服务**：`ragforge-backend`
2. **仅业务日志**：内容含 `logType=business`（推荐）
3. **按级别**：`level=ERROR` / `WARN` / `INFO`
4. **按 logger**：包名前缀 `com.ragforge`（如 `com.ragforge.search`、`com.ragforge.controller`）
5. **按链路**：从 Trace 详情复制 `traceId`，在 Logs 中搜索同一 `traceId`

## 字段说明

每条 SkyWalking 业务日志包含：

`timestamp`、`level`、`logType=business`、`service`、`traceId`、`requestId`、`sessionId`、`userId`（若 MDC 有）、`thread`、`logger`、`message`、异常堆栈。

控制台可使用 `%highlight` 着色；SkyWalking 上报为纯文本 key=value，**不含 ANSI 颜色码**，UI 按 `level` 字段区分样式。

## 与 CareerMate 联查

跨服务请求时，CareerMate（`careermate-backend`）与 RAGForge 共用同一 OAP；在 Trace Topology 中点击 Span，用相同 `traceId` 分别在两侧 Logs 中过滤 `logType=business`。
