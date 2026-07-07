# RAGForge · RAG 知识引擎

<p align="left">
  <a href="README.md">简体中文</a> ·
  <a href="README.en.md">English</a>
</p>

> 面向 RAG 应用的知识检索基础设施 —— 文档导入、多模态向量化、混合检索、重排、RAG 应答、质量评测与统一认证;输出可追溯的检索结果(`query → chunks + scores + citations`),以干净的 REST API 与 MCP Server 供上层 Agent 调用。

[![Live Site](https://img.shields.io/badge/Live%20Site-ragforge.net-2EA043?logo=googlechrome&logoColor=white)](https://ragforge.net)
[![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.x-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15%2F16-4169E1?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Qdrant](https://img.shields.io/badge/Qdrant-1024d%20HNSW%20%2B%20INT8-DC244C?logo=qdrant&logoColor=white)](https://qdrant.tech/)
[![Elasticsearch](https://img.shields.io/badge/Elasticsearch-8.15-005571?logo=elasticsearch&logoColor=white)](https://www.elastic.co/)
[![RocketMQ](https://img.shields.io/badge/RocketMQ-5.x-D77310?logo=apacherocketmq&logoColor=white)](https://rocketmq.apache.org/)
[![MCP](https://img.shields.io/badge/MCP-Spring%20AI%201.0-6DB33F?logo=spring&logoColor=white)](https://docs.spring.io/spring-ai/reference/)
[![LLM](https://img.shields.io/badge/LLM-DashScope%20%2F%20DeepSeek-FF6A00)](https://dashscope.aliyun.com/)
[![Deploy](https://img.shields.io/badge/Deploy-k3s%20%2B%20ACR-326CE5?logo=kubernetes&logoColor=white)](docs/deploy/deployment-architecture.md)

---

## 目录

- [项目定位](#项目定位)
- [核心能力](#核心能力)
- [架构概览](#架构概览)
- [技术栈](#技术栈)
- [检索策略](#检索策略)
- [多模态与向量空间](#多模态与向量空间)
- [模型与成本中心](#模型与成本中心)
- [认证与权限模型](#认证与权限模型)
- [项目结构](#项目结构)
- [前端页面](#前端页面)
- [快速开始(本地开发)](#快速开始本地开发)
- [API 示例](#api-示例)
- [MCP Server](#mcp-server)
- [数据库迁移](#数据库迁移)
- [部署架构](#部署架构)
- [文档导航](#文档导航)
- [当前状态](#当前状态)

---

## 项目定位

RAGForge 是一个面向 RAG 应用的**知识库检索基础设施服务**,负责文档导入、解析、清洗、切分、(多模态)向量化、关键词索引、混合检索、重排、RAG 应答、检索调试、质量评测、统一认证鉴权和 API 输出。

它本身不是问答机器人,核心输出是**可追溯的检索结果**:

```text
query -> chunks + scores + citations + metadata + 分段耗时(rewrite/vector/keyword/rerank/total)
```

上层 Agent、问答系统或业务应用可以调用 RAGForge 获取候选上下文(`/api/v1/search`),或直接使用内置的 RAG 应答(`/api/v1/answer`),也可以通过 MCP 协议把检索能力接入 AI 工具链。本项目是 [CareerMate(AI 求职 Agent)](https://github.com/dezhiguan/careermate) 的知识检索底座。

## 核心能力

- **知识库与文档管理**:支持 PDF、Markdown、TXT、Word/OOXML 等常见格式,Tika 解析。
- **异步文档处理管道**:上传 → 存储 → RocketMQ → 解析 → 清洗 → 分块 → Embedding → PG/ES 入库,全程状态可查。
- **多模态统一向量空间**:文本与图片统一编码到 **同一向量空间**(DashScope `qwen3-vl-embedding` 多模态 embedding,Matryoshka 截断至 **1024 维**),支持以文搜图/图文混合检索;向量存储与 ANN 检索由 **Qdrant** 承载(HNSW + INT8 量化)。
- **5 种检索策略**:`vector` / `keyword` / `hybrid(RRF 融合)` / `rewrite(改写+多路召回)` / `full(改写+混合+rerank 精排)`,每种策略带独立并发限流与超时保护。
- **RAG 应答**:`POST /api/v1/answer`,SSE 流式返回答案 + 引用片段。
- **检索调试台**:对比不同策略、权重、TopK 参数下的召回与排序。
- **质量评测 + LLM-as-Judge**:构建评测集观察召回/排序/失败样本;离线 Golden Set 与在线抽样由 DeepSeek 充当裁判自动打分,质量看板内置。
- **统一认证与权限**:后台接口使用 Auth Gateway 颁发的 Bearer JWT(RS256 + JWKS 校验);支持账号密码/短信验证码登录、刷新令牌、退出、全端退出、密码重置;角色 `ADMIN` / `KB_EDITOR` / `KB_VIEWER` / `SERVICE_ACCOUNT`;知识库通过 `kb_acl`、JWT claims 和组织模型做细粒度读写控制;Auth Gateway 的会话撤销/密码变更事件经 HMAC webhook 同步,Redis 维护撤销名单。
- **组织模型**:GitHub 式"个人 + 组织"协作(已移除早期 tenant 多租户),知识库归属 `owner_user_id` / `org_id`,支持组织邀请与通知。
- **API Key 管理**:为外部系统和 MCP 工具提供受控调用,支持启停、服务账号上下文、知识库范围(`allowed_kb_ids`)和 Redis 分钟级限流。
- **MCP Server**:无状态 Streamable HTTP 端点 `/mcp`,暴露 `search_knowledge`、`list_knowledge_bases`、`answer_with_citations` 三个工具(早期 SSE 传输已弃用)。
- **模型注册表 & 成本中心**:模型统一注册(`model_config`),按模型/组织维度计量计价(`model_usage_daily`);改写与应答支持运行时动态选型与 fallback。
- **元数据过滤检索**:检索请求支持 `filter.chunkType` 等参数。
- **文本直传接口**:`POST /api/v1/documents`(text 通道)已解析文本直接入库,避免二次解析。

## 架构概览

```text
                         +------------------------------+
                         |         Vue 3 Frontend       |
                         | Login / Dashboard / KB /     |
                         | Debug / Eval / Answer / API  |
                         +---------------+--------------+
                                         | HTTPS
                                         v
   +-----------------+        +------------------------------+
   |  Auth Gateway   |<-------|       Spring Boot Backend     |
   | (独立 IdP 服务)  | JWKS   |  (同一镜像, RAGFORGE_ROLE 区分) |
   |  JWT 颁发/JWKS   |  +HMAC |  ┌────────┬─────────┬───────┐ |
   +-----------------+ webhook|  │  api   │ worker  │ judge │ |
                              |  │ REST/  │ MQ 文档 │ LLM-as│ |
                              |  │ Search/│ 处理    │ Judge │ |
                              |  │ Answer │ consumer│ 评测  │ |
                              |  └───┬────┴────┬────┴───┬───┘ |
                              +------|---------|--------|------+
                          async job  |   model |  judge |
                                     v   calls v        v
                        +------------------+  +----------------------+
                        |    RocketMQ      |  |  DashScope / DeepSeek |
                        | document-process |  |  embedding / rewrite /|
                        +--------+---------+  |  answer / rerank /judge|
                                 |            +----------------------+
                                 v
            +-------------------------------------------------+
            |                   Data Layer                    |
            | Qdrant(向量 1024d/HNSW/INT8) /                    |
            | PostgreSQL(业务数据 + chunk 正文/元数据) /          |
            | Elasticsearch(BM25) / Redis                      |
            +-------------------------------------------------+
```

认证链路:

```text
Browser
  -> RAGForge /api/auth/*  (代理到 Auth Gateway)
  -> access token + HttpOnly refresh cookie
  -> RAGForge /api/v1/* with Bearer JWT
  -> JwtVerifier: JWKS 验签 + issuer/audience + 撤销名单 + 角色/scope + KB ACL
```

外部检索链路:

```text
Client / Agent / MCP
  -> /api/v1/search | /api/v1/answer | /mcp
  -> Bearer JWT 或 X-API-Key
  -> SERVICE_ACCOUNT / user 上下文
  -> 按可读 KB 范围过滤
```

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 后端 | Java 21, Spring Boot 3.5.15 |
| AI / MCP | Spring AI 1.0.0(`spring-ai-starter-mcp-server-webmvc`) |
| 安全 | Spring Security, 自研 JWT 校验(RS256 + JWKS), HMAC webhook, Redis 撤销名单 |
| ORM | MyBatis-Plus 3.5.16 |
| 数据库 | PostgreSQL(业务数据 + chunk 正文/元数据) |
| 向量检索 | Qdrant(官方 Java gRPC client 1.12.0,collection `ragforge_chunks`,1024 维,HNSW ANN + INT8 量化,oversampling 2.0) |
| 关键词检索 | Elasticsearch 8.15.x(BM25, IK 分词,缺失回退 standard) |
| 消息队列 | RocketMQ(spring-boot-starter 2.3.3) |
| 缓存 / 限流 / 撤销 / 分布式锁 | Redis, Caffeine, ShedLock 5.16 |
| 文档解析 | Apache Tika 2.9.3 |
| 对象存储 | 阿里云 OSS SDK 3.18.3(抽象层,默认本地盘) |
| 可观测 | Micrometer + Prometheus, SkyWalking Agent 9.3 |
| Embedding / Rewrite / Answer / Rerank | DashScope(`qwen3-vl-embedding` 截断 1024 维 / `qwen-turbo` / `qwen-plus` / `qwen3-rerank`) |
| LLM-as-Judge | DeepSeek(`deepseek-v4-flash`) |
| 前端 | Vue 3.4, Vite 5, Vue Router 4, Element Plus 2, Axios(纯 JavaScript) |
| 部署 | k3s(应用层),数据层独立机,入口层 Nginx |

## 检索策略

所有策略共用统一的 `RetrievalService`,检索调试台、评测、诊断页共享同一套链路;返回并记录分段耗时(rewrite / vector / keyword / rerank / total)。

| 策略 | 链路 | 是否 Rerank | 默认并发上限 | 默认超时 |
| --- | --- | --- | --- | --- |
| `vector` (默认) | Qdrant ANN(HNSW,余弦)| 否 | 48 | 5s |
| `keyword` | Elasticsearch BM25 | 否 | 40 | 5s |
| `hybrid` | vector + keyword 并行 + RRF 融合(带 vectorWeight 加权)| 否 | 32 | 5s |
| `rewrite` | Query 改写(qwen-turbo)+ 多路向量召回 | 否 | 8 | 10s |
| `full` | 改写 → 多查询混合(RRF)→ DashScope rerank 精排 | **是** | 4 | 15s |

- `full` 是唯一调用 rerank 的策略,默认并发上限最低(4),避免重链路拖垮在线服务。
- 并发上限均可经 `RAGFORGE_RETRIEVAL_*_CONCURRENCY` 环境变量覆盖;多副本下由 **Redis 分布式限流**(ZSET + 租约)做全局收口,单机 fail-open。
- 受控检索线程池(`retrieval-` 前缀)并行执行 hybrid/full 的多路召回;触发限流返回 429,超时返回 504。
- query 向量、改写结果、rerank 结果均有缓存,命中即跳过对应的 DashScope 调用(不重复计量 token)。

## 多模态与向量空间

- 文本与图片统一编码到 **同一个向量空间**(DashScope `qwen3-vl-embedding` 多模态 embedding 接口);利用 Matryoshka 表示,向量截断至 **1024 维**以匹配 Qdrant collection 配置。
- **向量存储已从 pgvector 迁移到 Qdrant**(collection `ragforge_chunks`,1024 维,**HNSW ANN + INT8 标量量化**,oversampling 2.0,余弦距离,内网 gRPC 访问)。检索流为:`query → embedding(1024) → Qdrant ANN(按 kb_id / doc_id / chunk_type 过滤)→ 拿 chunkId + score → 回 PostgreSQL 按 chunkId 批量取正文/元数据 → 组装 SearchResult`(见 `backend/src/main/java/com/ragforge/search/QdrantVectorStore.java`、`VectorSearchService.java`)。
- PostgreSQL 仍承载业务数据与 chunk 正文/元数据;历史 `document_chunks.vl_vector` 列**已留空、不再入库、不再用于检索**(历史手工迁移 `db/manual/V27__vl_unified_vector.sql` 仅作留档)。
- 图片文档走独立的图片处理管道(`ImagePipelineService`),可选 OCR(`qwen-vl-ocr`)与图片描述。

## 模型与成本中心

| 用途(Purpose) | 当前模型 | 供应商 | 备注 |
| --- | --- | --- | --- |
| EMBEDDING | `qwen3-vl-embedding` | DashScope | 文本+图片统一,Matryoshka 截断至 1024 维(匹配 Qdrant collection) |
| REWRITE | `qwen-turbo` | DashScope | Query 改写,支持运行时动态选型 |
| ANSWER | `qwen-plus` | DashScope | RAG 应答 / 调试台,支持运行时动态选型 |
| RERANK | `qwen3-rerank` | DashScope | 仅 `full` 策略调用 |
| OCR | `qwen-vl-ocr` | DashScope | 图片管道可选 |
| JUDGE | `deepseek-v4-flash` | DeepSeek | LLM-as-Judge 评测 |

- 模型在 `model_config` 表统一注册(code / vendor / purpose / 单价 / 主备 / fallback),日用量与成本汇总进 `model_usage_daily`(支持按 `org_id` 组织维度分摊)。
- 动态选型(主→fallback→任一可用)目前接入 **REWRITE / ANSWER**;EMBEDDING / RERANK / OCR / JUDGE 走配置默认模型并参与计量计价。
- 历史说明:早期设计中 rerank 曾计划用本地 Python 微服务(`reranker/`,jina-reranker),现已改为 DashScope 在线 `qwen3-rerank`;`reranker/` 目录作为历史/可选预留,**线上未部署**。

## 认证与权限模型

核心入口与鉴权方式:

| 入口 | 鉴权方式 | 说明 |
| --- | --- | --- |
| `/api/auth/**` | 公开 | 代理 Auth Gateway,处理登录/刷新/退出/密码重置/userinfo |
| `/api/v1/health`、`/actuator/health` | 公开 | 健康检查 |
| `/api/v1/.well-known/...jwks.json` | 公开 | RAGForge 后端 client assertion 公钥 |
| `/api/v1/events/**` | HMAC | Auth Gateway 事件 webhook(HmacSHA256 + 时间戳) |
| `/api/v1/search`、`/api/v1/answer`、`/mcp` | JWT 或 API Key | 外部检索 / RAG 应答 / MCP(无状态 Streamable HTTP) |
| 其他 `/api/v1/**` | JWT | 后台管理 API |

角色:

| 角色 | 主要能力 |
| --- | --- |
| `ADMIN` | 访问非 SYSTEM 知识库,管理 API Key,执行维护任务(破玻璃提权写审计) |
| `KB_EDITOR` | 读写被授权知识库,运行评测和诊断 |
| `KB_VIEWER` | 读取被授权知识库并运行检索调试 |
| `SERVICE_ACCOUNT` | 由 API Key 创建,仅能访问 API Key 允许的知识库 |

知识库访问由统一的 `KbAccessGuard` 裁决:SYSTEM 库永不可访问 → ADMIN(破玻璃)可访问任意非 SYSTEM 库 → owner 放行 → PUBLIC 库可读 → 组织库按 org 角色 → 回退 JWT claims(`rag_readable/writable_kb_ids`)或 `kb_acl`。

详见 [docs/dev/auth-and-permissions.md](docs/dev/auth-and-permissions.md)。

## 项目结构

```text
rag-forge/
├── backend/                 # Spring Boot 后端(api / worker / judge 同一镜像,RAGFORGE_ROLE 区分角色)
├── frontend/                # Vue 3 前端(纯 JS)
├── reranker/                # 历史/可选 Python Reranker 预留(线上未部署,rerank 实走 DashScope)
├── docker/                  # 本地中间件配置
├── deploy/                  # k3s 清单、Nginx、环境变量模板和部署脚本
├── docs/
│   ├── architecture.md      # 权威架构文档(以此为准)
│   ├── prototype/           # 早期设计稿与原型(历史)
│   ├── dev/                 # 实现说明、任务、路线、设计规格
│   ├── test/                # 测试计划、用例、验收报告、排障
│   └── deploy/              # 部署、运维、监控
├── docker-compose*.yml      # 本地中间件 / 历史单机部署编排
└── deploy.sh                # 部署脚本
```

## 前端页面

主要路由(基于角色/scope 控制可见性,真实权限以后端为准):

- **Login / Register / ResetPassword** — 登录、注册、密码重置。
- **Dashboard(`/`)** — 知识库、文档、分块和最近活动概览。
- **KnowledgeBase(`/knowledge`)/ KnowledgeDocuments** — 知识库与文档管理。
- **UploadWizard(`/uploads/wizard`)** — 文档上传向导。
- **DocumentDetail(`/document/:id`)** — 文档解析状态与分块结果。
- **DebugConsole(`/debug`)** — 检索策略调试。
- **AnswerPlayground(`/answer`)** — RAG 应答(流式 + 引用)。
- **EvaluationLab(`/eval`)/ EvaluationQuality(`/evaluation/quality`)** — 评测集与 LLM-as-Judge 质量看板。
- **ModelCostCenter(`/models`)** — 模型注册与成本看板。
- **DeveloperCenter(`/api`)** — API Key 与外部调用管理。
- **OrgList(`/orgs`)/ Organizations(`/orgs/manage`)** — 组织列表与组织管理。
- **AccountSettings(`/account`)** — 账号设置。
- **PerformanceProbe(`/perf-probe`)** — 检索链路耗时诊断。
- **Forbidden(`/403`)** — 无权访问提示。

## 快速开始(本地开发)

### 1. 启动中间件

```bash
docker compose up -d   # PostgreSQL / Elasticsearch / RocketMQ(向量库 Qdrant 与 Redis 见下)
```

默认端口:PostgreSQL `5433`、Elasticsearch `9200`、RocketMQ NameServer `9876`、Broker `10911`。**Qdrant**(向量库,gRPC `6334`)与 **Redis**(认证撤销、API Key 限流、ShedLock)当前不在 `docker-compose.yml` 内,需另行提供,例如:

```bash
docker run -d --name ragforge-qdrant -p 6333:6333 -p 6334:6334 qdrant/qdrant
```

collection `ragforge_chunks`(1024 维、Cosine、HNSW、INT8 量化)首次使用前需在 Qdrant 侧建好。

### 2. 配置环境变量

```bash
cp backend/.env.example backend/.env
```

至少配置:

```properties
DASHSCOPE_API_KEY=your-dashscope-api-key
DEEPSEEK_API_KEY=your-deepseek-api-key        # LLM-as-Judge 评测需要
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
QDRANT_HOST=127.0.0.1
QDRANT_GRPC_PORT=6334
QDRANT_COLLECTION=ragforge_chunks
RAGFORGE_VL_DIM=1024
```

后台登录与 JWT 认证还需配置 Auth Gateway(issuer / audience / JWKS / token 代理 / client assertion 私钥 / HMAC secret),详见 [docs/dev/auth-and-permissions.md](docs/dev/auth-and-permissions.md)。本地 `dev` profile 下文件存储默认走本地盘。

### 3. 启动后端

```bash
cd backend && mvn spring-boot:run
# 健康检查: http://localhost:8080/api/v1/health
```

### 4. 启动前端

```bash
cd frontend && npm install && npm run dev
# 前端: http://localhost:5173
```

## API 示例

检索接口(JWT 或 API Key):

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-ragforge-dev" \
  -d '{
    "query": "候选人有哪些 Java 项目经验?",
    "strategy": "full",
    "topK": 5,
    "filter": { "chunkType": ["resume", "project"] }
  }'
```

RAG 应答(SSE 流式):

```bash
curl -N -X POST http://localhost:8080/api/v1/answer \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-ragforge-dev" \
  -d '{ "query": "总结候选人的后端技术栈", "kbId": 16 }'
```

说明:

- `sk-ragforge-dev` 仅在 `dev` profile 下由 `DevApiKeyConfig` 提供;生产请在开发者中心(`/api`)或 `api_keys` 表创建 `sk-rf-*` Key。
- API Key 请求转换为 `SERVICE_ACCOUNT` 上下文,按 `allowed_kb_ids` 过滤;默认分钟限流 100,Redis 异常时 fail-open。
- 普通后台管理接口只接受 Auth Gateway 颁发的 JWT,不接受 API Key。

## MCP Server

server name `ragforge-mcp-server`,暴露三个工具:

- `search_knowledge` — 按策略检索知识库
- `list_knowledge_bases` — 列出可访问的知识库
- `answer_with_citations` — RAG 应答并返回引用

传输方式:三个工具统一走**无状态 Streamable HTTP 端点 `/mcp`**(`StreamableMcpController`),均按调用方可读 KB 范围过滤。早期的 SSE 传输(`/sse` + `/mcp/message`)在多副本下会话内存态不粘滞(`/mcp/message` 404),已按 MCP 标准弃用并关闭(`spring.ai.mcp.server.enabled=false`)。

## 数据库迁移

后端启用 Flyway(`baseline-version=26`、`out-of-order=true`),迁移文件位于 `backend/src/main/resources/db/migration`,当前最新 `V59`。核心演进:

- `V1` 初始 9 表;`V4/V7` chunk_type;`V6` HNSW 索引(pgvector 时代,现已随向量迁出 Qdrant 而废弃)。
- `V9/V10/V12` 知识库 owner/visibility、`kb_acl`、API Key 扩展。
- `V19~V26` 身份标识、清洗 profile、chunker profile、多模态图片 chunk。
- `V28` RAG 应答(`answer_logs`);`V30/V32` LLM-as-Judge(`judge_results` 等)。
- `V35~V38` 模型成本中心 + rerank 主备修正(`qwen3-rerank` 为主)。
- `V42` JWT 撤销名单;`V44` 破玻璃审计;`V45` 组织模型(移除 `tenant_id`)。
- `V52/V53` API Key 授权级别与去明文;`V55/V56` 组织判分预算 + 系统组织;`V57/V59` 评测 core question 冻结。
- **历史手工迁移** `db/manual/V27__vl_unified_vector.sql`:pgvector 时代的统一向量列脚本;向量现已迁至 Qdrant,`document_chunks.vl_vector` 列留空不再使用,该脚本仅作留档。

## 部署架构

生产采用**物理三层分离**,但**应用层运行在 k3s** 上(不再是 docker-compose):

| 层 | 角色 | 组件 |
| --- | --- | --- |
| 数据层(独立节点) | 状态层 | Qdrant(向量库,gRPC 6334)、PostgreSQL、Elasticsearch、Redis、RocketMQ(裸装,未进 k8s) |
| 入口层(独立节点) | 接入层 | 宿主机 Nginx:静态前端 + `/api/` 反代到应用层 NodePort |
| 应用层(k3s 单节点) | 计算层 | `ragforge` 命名空间下的全部 pod |

`ragforge` 命名空间(**同一个 backend 镜像,通过 `RAGFORGE_ROLE` 区分角色**):

| Deployment | 角色 | 副本 | Service |
| --- | --- | --- | --- |
| `ragforge-api` | REST / Search / Answer | 3 | NodePort `8080:31090` |
| `ragforge-frontend` | 前端(集群内) | 2 | NodePort `80:31002` |
| `ragforge-worker` | RocketMQ 文档处理 consumer | 2 | 无(纯后台) |
| `ragforge-judge` | LLM-as-Judge 评测 | 1 | 无(纯后台) |

入口链路:`域名(443) → 入口层 Nginx → 应用层 NodePort 31090 → ragforge-backend Service → api pod`。Auth Gateway 在同集群独立命名空间,RAGForge 通过集群内 DNS(`auth-gateway.auth-gateway.svc:8090`)消费其 JWKS 与 token 代理。

文件存储当前为节点本地 `hostPath /data/files`(单节点可用);OSS 抽象层(`ObjectStorage`)已就绪,跨节点多实例前建议切换到 OSS/NAS。k3s 清单见 [`deploy/k8s/ragforge/`](deploy/k8s/ragforge/),完整说明见 [docs/deploy/deployment-architecture.md](docs/deploy/deployment-architecture.md)。

> 历史部署文档(docker-compose 三层)保留在 `docs/deploy/` 中并已标注为历史口径,**以 `deployment-architecture.md` 为准**。

## 文档导航

- 📐 [架构设计(权威)](docs/architecture.md)
- 🛠️ 开发:[认证与权限](docs/dev/auth-and-permissions.md) · [安全与组织模型](docs/dev/security-and-multitenancy.md) · [模型成本中心](docs/dev/model-cost-center-design.md) · [重构路线](docs/dev/current-architecture-and-refactor-roadmap.md)
- 🚀 部署:[部署架构(k3s,权威)](docs/deploy/deployment-architecture.md) · [OSS CORS](docs/deploy/oss-cors-setup.md) · [SkyWalking 业务日志](docs/deploy/skywalking-business-logs.md)
- 🧪 测试:[检索质量测试](docs/test/retrieval-quality-test-plan-V1.md) · [V5 验收用例](docs/test/v5-acceptance-playwright-cases.md)
- 🗂️ [版本演进时间线(CHANGELOG)](docs/CHANGELOG.md) · [文档索引](docs/INDEX.md)

## 当前状态

RAGForge 已上线运行(见[线上环境](https://ragforge.net)),核心的导入、(多模态)索引、检索、RAG 应答、调试、评测、LLM-as-Judge、API Key、统一认证、组织权限和 k3s 部署链路均已跑通。

当前线上验证规模(参考):

```text
约 10,000 份文档 / 约 100,000 个 Chunk / 8 个知识库
```

实际容量取决于文档长度、切分参数、Embedding 速度、ES/PG/Qdrant 参数和资源。向量检索已由 Qdrant HNSW ANN 承载(见[多模态与向量空间](#多模态与向量空间)),具备近似索引与横向扩展空间。

后续路线见 [docs/dev/current-architecture-and-refactor-roadmap.md](docs/dev/current-architecture-and-refactor-roadmap.md):文件存储切 OSS、API/worker/judge 资源隔离深化、Qdrant 分片/副本与召回质量调优、评测指标增强、可观测告警完善等。

## License

尚未声明开源协议。如需复用请联系作者。
