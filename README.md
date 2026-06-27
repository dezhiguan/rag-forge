# RAGForge

RAGForge 是一个面向 RAG 应用的知识库检索基础设施服务。

它负责文档导入、解析、切分、向量化、关键词索引、混合检索、检索调试、质量评测、认证鉴权和 API 输出。RAGForge 本身不是聊天应用，核心输出是可追溯的检索结果：

```text
query -> chunks + scores + metadata + latency
```

上层 Agent、问答系统或业务应用可以调用 RAGForge 获取候选上下文，再由自己的 LLM 流程生成最终答案。

## 核心能力

- 知识库和文档管理。
- 支持 PDF、Markdown、TXT、Word 等常见文档格式解析。
- 文档切分、Embedding 生成和 RocketMQ 异步索引。
- PostgreSQL + pgvector 存储业务数据和向量。
- Elasticsearch 构建 BM25 关键词索引。
- 多种检索策略：
  - `vector`：向量语义检索
  - `keyword`：Elasticsearch BM25 关键词检索
  - `hybrid`：向量 + 关键词 + RRF 融合
  - `rewrite`：Query 改写 + 多路向量召回
  - `full`：Query 改写 + 混合召回 + Rerank 精排
- 检索调试台：对比不同策略、权重和 TopK 参数。
- 评测实验室：构建评测集并观察召回、排序和失败样本。
- 性能诊断页：手动查看检索链路分段耗时。
- 统一认证与权限：
  - 后台管理接口使用 Auth Gateway 颁发的 Bearer JWT。
  - JWT 通过 issuer、audience、JWKS、公钥缓存和时钟偏移校验。
  - 前端支持账号密码登录、手机号验证码登录、刷新令牌、退出登录、全端退出和密码重置。
  - 角色支持 `ADMIN`、`KB_EDITOR`、`KB_VIEWER`、`SERVICE_ACCOUNT`。
  - 知识库访问通过 `kb_acl`、JWT claims 和 API Key 允许的知识库列表做细粒度读写控制。
  - Auth Gateway 会话撤销和密码变更事件通过 HMAC webhook 同步到 RAGForge，Redis 保存撤销状态。
- API Key 管理：为外部系统和 MCP 工具提供受控 API 调用，支持启停、服务账号上下文、知识库范围和 Redis 分钟级限流。
- 文本直传接口：`POST /api/v1/documents/text`，已解析文本直接入库，支持 `chunkType` 语义标注，避免 Tika 二次解析。
- 元数据过滤检索：检索请求支持 `filter.chunkType` 参数，向量和关键词两路均可按 chunk 类型精确过滤。
- MCP Server：基于 Spring AI MCP HTTP SSE，暴露 `searchKnowledgeBase` 和 `listKnowledgeBases` 工具，可通过 `http://localhost:8080/sse` 调用 RAGForge 检索能力。

## 架构概览

```text
                  +----------------------+
                  |      Vue Frontend    |
                  | Login / Dashboard /  |
                  | Debug / Eval / API   |
                  +----------+-----------+
                             |
                             v
                  +----------------------+
                  | Spring Boot Backend  |
                  | Auth Proxy / REST /  |
                  | Pipeline / Search    |
                  +-----+----------+-----+
                        |          |
             async job  |          | model calls
                        v          v
               +---------------+  +----------------------+
               |   RocketMQ    |  | DashScope API         |
               | document jobs |  | embedding/rewrite/    |
               +-------+-------+  | rerank                |
                       |          +----------------------+
                       v
        +---------------------------------------------+
        |                 Data Layer                  |
        | PostgreSQL + pgvector / Elasticsearch / Redis|
        +---------------------------------------------+
```

认证链路：

```text
Browser
  -> RAGForge /api/auth/*
  -> Auth Gateway
  -> access token + HttpOnly refresh cookie
  -> RAGForge /api/v1/* with Bearer JWT
  -> JWKS verify + role/scope check + KB ACL check
```

外部检索链路：

```text
Client / MCP
  -> /api/v1/search or /sse
  -> Bearer JWT or X-API-Key
  -> SERVICE_ACCOUNT / user context
  -> allowed KB filtering
```

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 后端 | Spring Boot 3.5.x, Java 21 |
| 安全 | Spring Security, JWT, JWKS, HMAC webhook, Redis revocation store |
| ORM | MyBatis-Plus |
| 数据库 | PostgreSQL 15 |
| 向量检索 | pgvector |
| 关键词检索 | Elasticsearch 8.x |
| 消息队列 | RocketMQ 5.x |
| 缓存 / 限流 / 撤销状态 | Redis, Caffeine |
| 文档解析 | Apache Tika, PDFBox |
| Embedding / Query Rewrite / Rerank | DashScope API |
| 前端 | Vue 3, Vite, Axios, Vue Router |
| 部署 | Docker Compose, Nginx, SkyWalking Java Agent |

## 项目结构

```text
rag-forge/
├── backend/                 # Spring Boot 后端服务
├── frontend/                # Vue 3 前端
├── reranker/                # 可选 Python Reranker 服务预留
├── docker/                  # 中间件配置
├── docs/                    # 架构、认证权限、部署、路线图
├── deploy/                  # K8s、Nginx、环境变量和部署脚本
├── docker-compose.yml           # 本地中间件环境
├── docker-compose-data.yml      # Server 1 数据与检索层
├── docker-compose-ingress.yml   # Server 2 入口层（Nginx + 前端）
├── docker-compose-backend.yml   # Server 3 应用层（RAGForge 后端 3 副本）
├── docker-compose-app.yml       # LEGACY 单机模式（Nginx + 后端同机）
├── deploy.sh                    # 三层部署脚本
└── nginx.conf                   # Nginx 前端和 API 代理配置
```

## 页面功能

- Login：账号密码登录、手机号验证码登录。
- Reset Password：短信验证后重置密码。
- Dashboard：知识库、文档、分块和最近活动概览。
- Knowledge Base：创建知识库、上传和管理文档。
- Document Detail：查看文档解析状态和分块结果。
- Debug Console：调试检索策略并查看返回 Chunk。
- Evaluation Lab：管理评测集并分析检索质量。
- API Gateway：管理 API Key 和外部调用。
- Performance Probe：手动诊断检索链路耗时。
- Forbidden：当前角色或 scope 无权访问时展示。

前端路由按角色和 scope 控制可访问页面，默认角色能力在 `frontend/src/composables/useAuth.js` 中兜底，真实权限仍以后端校验为准。

## 环境要求

- JDK 21+
- Maven 3.8+
- Node.js 18+
- Docker 和 Docker Compose
- Redis
- DashScope API Key
- 可选：Auth Gateway。如果没有本地 Auth Gateway，只能使用开发 API Key 调试 `/api/v1/search`、MCP 等外部检索入口，后台页面需要有效 JWT。

## 快速开始

### 1. 启动中间件

本地开发可以先启动 PostgreSQL、Elasticsearch 和 RocketMQ：

```bash
docker compose up -d
```

默认端口：

- PostgreSQL + pgvector：`localhost:5433`
- Elasticsearch：`localhost:9200`
- RocketMQ NameServer：`localhost:9876`
- RocketMQ Broker：`localhost:10911`

Redis 没有包含在根目录 `docker-compose.yml` 中，开发配置默认读取 `REDIS_HOST` 和 `REDIS_PORT`。认证事件撤销、API Key 限流和 ShedLock 都依赖 Redis，建议本地提供一个 Redis 实例。

### 2. 配置环境变量

复制环境变量模板：

```bash
cp backend/.env.example backend/.env
```

编辑 `backend/.env`，至少配置以下内容：

```properties
DASHSCOPE_API_KEY=your-dashscope-api-key
POSTGRES_HOST=127.0.0.1
POSTGRES_PORT=5433
POSTGRES_DB=ragforge
POSTGRES_USER=ragforge
POSTGRES_PASSWORD=ragforge
ELASTICSEARCH_HOST=localhost
ELASTICSEARCH_PORT=9200
ROCKETMQ_NAMESRV_ADDR=127.0.0.1:9876
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
```

如需启用后台登录和 JWT 认证，请同时配置 Auth Gateway：

```properties
RAGFORGE_AUTH_ISSUER=https://auth.careermate.cn
RAGFORGE_AUTH_AUDIENCE=ragforge-admin-api
RAGFORGE_AUTH_JWKS_URL=http://127.0.0.1:8090/.well-known/jwks.json
RAGFORGE_AUTH_PROXY_BASE_URL=http://127.0.0.1:8090
RAGFORGE_AUTH_PROXY_CLIENT_ID=ragforge-admin-backend
RAGFORGE_AUTH_PROXY_TARGET_AUDIENCE=ragforge-admin-api
RAGFORGE_AUTH_PROXY_TOKEN_ENDPOINT_AUDIENCE=https://auth.careermate.cn/oauth/token
RAGFORGE_AUTH_PROXY_CLIENT_ASSERTION_PRIVATE_KEY=
RAGFORGE_AUTH_PROXY_CLIENT_ASSERTION_KID=ragforge-admin-backend
RAGFORGE_AUTH_PROXY_PUBLIC_KEY_PEM=
RAGFORGE_AUTH_PROXY_COOKIE_SECURE=false
RAGFORGE_AUTH_EVENT_HMAC_SECRET=dev-secret-must-match-authgw
```

认证和权限配置详见 [docs/auth-and-permissions.md](docs/auth-and-permissions.md)。

### 3. 启动后端

```bash
cd backend
mvn spring-boot:run
```

健康检查：

```text
http://localhost:8080/api/v1/health
```

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端地址：

```text
http://localhost:5173
```

Vite 开发服务当前把 `/api/v1` 代理到后端。认证接口 `/api/auth` 在生产环境由 Nginx 同源转发；本地调试登录能力时，需要额外配置前端代理或通过后端同源入口访问。

## 构建

后端构建：

```bash
cd backend
mvn clean package -DskipTests
```

前端构建：

```bash
cd frontend
npm install
npm run build
```

## API 示例

后台接口默认使用 Bearer JWT：

```bash
curl http://localhost:8080/api/v1/kb \
  -H "Authorization: Bearer <access-token>"
```

检索接口支持 Bearer JWT 或 API Key：

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-ragforge-dev" \
  -d '{
    "query": "候选人有哪些 Java 项目经验？",
    "strategy": "full",
    "topK": 5,
    "filter": {
      "chunkType": ["resume", "project"]
    }
  }'
```

说明：

- `sk-ragforge-dev` 只在 `dev` profile 下由 `DevApiKeyConfig` 提供，用于本地调试 `/api/v1/search`、`/mcp/**`、`/sse`。
- 生产环境应通过 API Gateway 页面或 `api_keys` 表创建 `sk-rf-*` API Key。
- API Key 请求会转换成 `SERVICE_ACCOUNT` 上下文，并按 `allowed_kb_ids` 过滤可检索知识库。
- API Key 默认分钟限流为 `100`，Redis 异常时 fail-open 放行业务请求并记录告警。
- 普通后台管理接口不接受 API Key，只接受 Auth Gateway 颁发的 JWT。

## 认证与权限模型

核心入口：

| 入口 | 鉴权方式 | 说明 |
| --- | --- | --- |
| `/api/auth/**` | 公开 | RAGForge 代理到 Auth Gateway，处理登录、刷新、退出、密码重置和 userinfo |
| `/api/v1/health`、`/actuator/health` | 公开 | 健康检查 |
| `/api/v1/.well-known/ragforge-admin-backend-jwks.json` | 公开 | RAGForge 后端自身 client assertion 公钥 |
| `/api/v1/events/**` | HMAC | Auth Gateway 事件 webhook，校验签名和时间戳 |
| `/api/v1/search`、`/mcp/**`、`/sse` | JWT 或 API Key | 面向外部检索和 MCP |
| 其他 `/api/v1/**` | JWT | 后台管理 API |

角色和权限：

| 角色 | 主要能力 |
| --- | --- |
| `ADMIN` | 可访问非系统知识库，管理 API Key，执行维护任务 |
| `KB_EDITOR` | 可读写被授权知识库，运行评测和诊断 |
| `KB_VIEWER` | 可读取被授权知识库并运行检索调试 |
| `SERVICE_ACCOUNT` | 由 API Key 创建，仅能访问 API Key 允许的知识库 |

知识库权限来源：

- `ADMIN`：可访问非 `SYSTEM` 类型知识库。
- 用户 JWT：优先使用 claims 中的 `rag_readable_kb_ids`、`rag_writable_kb_ids`，否则回退查询 `kb_acl`。
- API Key：使用 `api_keys.allowed_kb_ids` 作为可读写知识库范围。
- 文档读写会先解析文档所属知识库，再调用同一套 `KbAccessGuard`。

## 数据库迁移

后端启用 Flyway，迁移文件位于 `backend/src/main/resources/db/migration`。

当前与认证权限相关的迁移：

- `V9__add_kb_owner_and_visibility.sql`：给知识库增加 `tenant_id`、`owner_user_id`、`visibility`、`kb_type`。
- `V10__create_kb_acl.sql`：创建 `kb_acl`，并把知识库 owner 初始化为 admin 权限。
- `V12__extend_api_keys.sql`：给 API Key 增加 `principal_type`、`principal_id`、`scopes`、`allowed_kb_ids`，并移除旧的数据库开发 Key。

## 部署说明

生产环境采用三层架构，详见 [docs/deployment-three-tier.md](docs/deployment-three-tier.md)：

| 服务器 | 角色 | 组件 |
| --- | --- | --- |
| Server 1（172.25.90.183） | 数据层 | PostgreSQL、pgvector、Elasticsearch、Redis、RocketMQ（8C16G，数据层容器已按导入业务数据调优；Reranker 预留，当前不默认启动） |
| Server 2（8.163.63.222） | 入口层 | Nginx、RAGForge 前端、CareerMate 前端 |
| Server 3（8.138.191.228） | 应用层 | RAGForge backend 3 副本、CareerMate backend 3 副本、爬虫 |

Compose 文件：

- `docker-compose-data.yml` -> Server 1（数据与检索层资源限制随 Server 1 规格维护）
- `docker-compose-ingress.yml` -> Server 2
- `docker-compose-backend.yml` -> Server 3
- `docker-compose-app.yml` -> LEGACY 单机模式（兼容旧部署）

部署注意事项：

- Server 1 跑数据层，当前 PostgreSQL `4g`、Elasticsearch `5g`、RocketMQ Broker `2g`、Redis `512m`；Server 2 只跑 Nginx + 两个前端；Server 3 跑 RAGForge k3s backend 和 CareerMate k3s backend。
- 三个 RAGForge backend 副本共用 Server 3 宿主机 `/data/files`，后续多机器部署前应切换到 OSS、MinIO、NAS 或 NFS。
- RAGForge 与 CareerMate 前端共用 Nginx html 根目录：`/opt/rag-forge/frontend/dist/`。CareerMate 前端在子目录 `careermate/`，由 CareerMate CI 单独同步。
- Server 3 使用 `/opt/shared/env/common.env` 和 `/opt/shared/env/ragforge.env` 注入敏感配置，这些文件不入库、不随 CI 同步。
- Auth Gateway 的 issuer、JWKS、token 代理地址、client assertion 私钥、公钥和 HMAC webhook secret 必须通过服务器本地 env 配置。
- 迁移与切流步骤见 [docs/deployment-migration-runbook.md](docs/deployment-migration-runbook.md)。

当前版本更适合作为中小型知识库检索服务验证。一个比较务实的容量目标是：

```text
10,000 份文档 / 50,000 到 100,000 个 Chunk
```

实际容量取决于文档长度、切分参数、Embedding 调用速度、ES/PG 参数和服务器资源。

## 文档

- [认证与权限模型](docs/auth-and-permissions.md)
- [三层部署架构](docs/deployment-three-tier.md)
- [应用层多副本部署](docs/deployment-app-cluster.md)
- [迁移 Runbook](docs/deployment-migration-runbook.md)
- [架构设计](docs/architecture.md)
- [当前真实架构与重构路线](docs/current-architecture-and-refactor-roadmap.md)
- [测试计划](docs/ragforge-test-plan.html)

## 当前状态

RAGForge 已完成阶段性验收，核心的导入、索引、检索、调试、评测、API Key 管理、统一认证、知识库权限和线上部署流程已经跑通。

当前线上验证规模：

```text
8 个知识库 / 9,800 份文档 / 约 96,000 个 Chunk
```

已完成的工程化增强：

- 统一 `RetrievalService`，搜索接口、评测和诊断页共用同一套检索链路。
- 检索链路返回并记录分段耗时：rewrite、vector、keyword、rerank、total。
- `vector`、`hybrid`、`full` 增加策略级限流和服务端超时保护。
- `full` 默认限制并发 1，避免重链路拖垮在线服务。
- `hybrid` 中 keyword/vector 召回使用受控检索线程池并行执行。
- 增加 query embedding 本地缓存、Dashboard 缓存和知识库列表缓存。
- 补充 PostgreSQL/pgvector 相关查询索引 SQL。
- 接入 Spring Security、JWT、JWKS、Auth Gateway 代理、Auth 事件撤销和知识库 ACL。
- API Key 支持服务账号上下文、允许知识库范围和 Redis 分钟级限流。
- 完成 Server 3 单机三副本部署形态，配合 Nginx upstream 切流。

仍建议后续继续完善：

- 将本地文件存储切换为 OSS / MinIO / NAS，便于后端跨机器多实例部署。
- API 实例、文档处理 Worker、维护任务实例做角色隔离。
- 为 `full` 策略增加异步化、缓存或更明确的队列化能力。
- 引入 Prometheus/Grafana 或云监控告警，长期观察 PG、ES、JVM、Redis 和检索分段耗时。
- API Key 管理页面补齐 `allowedKbIds`、`scopes`、`principal`、`rateLimit` 的编辑能力。
- 评测集增加人工标注与 MRR/Top1 等更严格指标。
- 对认证事件 webhook 增加可观测指标和补偿排查工具。

## 开源说明

这个项目适合作为 RAG 检索系统、混合检索、工程化文档处理、评测闭环和统一认证接入的学习与实践项目。

如果要正式开源发布，建议补充：

- `LICENSE`
- 示例截图
- 更完整的 API 文档
- 一份可复现的 demo 数据集
- 本地 Auth Gateway 或 mock auth 的最小启动说明
