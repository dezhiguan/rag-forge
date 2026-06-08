# RAGForge

RAGForge 是一个面向 RAG 应用的知识库检索基础设施服务。

它负责文档导入、解析、切分、向量化、关键词索引、混合检索、检索调试、质量评测和 API 输出。RAGForge 本身不是聊天应用，核心输出是可追溯的检索结果：

```text
query -> chunks + scores + metadata + latency
```

上层 Agent、问答系统或业务应用可以调用 RAGForge 获取候选上下文，再由自己的 LLM 流程生成最终答案。

## 核心能力

- 知识库和文档管理。
- 支持 PDF、Markdown、TXT、Word 等常见文档格式解析。
- 文档切分、Embedding 生成和异步索引。
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
- API Key 管理：为外部系统提供受控 API 调用。
- 文本直传接口：`POST /api/v1/documents/text`，已解析文本直接入库，支持 `chunkType` 语义标注，避免 Tika 二次解析。
- 元数据过滤检索：检索请求支持 `filter.chunkType` 参数，向量和关键词两路均可按 chunk 类型精确过滤。
- MCP Server：基于 Spring AI MCP（HTTP_SSE），暴露 `searchKnowledgeBase` 和 `listKnowledgeBases` 两个工具，Claude Desktop 可直接连接 `http://localhost:8080/sse` 调用 RAGForge 检索能力

## 架构概览

```text
                  +----------------------+
                  |      Vue Frontend    |
                  | Dashboard / Debug /  |
                  | Eval / API Gateway   |
                  +----------+-----------+
                             |
                             v
                  +----------------------+
                  | Spring Boot Backend  |
                  | REST API / Pipeline  |
                  | Search / Evaluation  |
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

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 后端 | Spring Boot 3.2, Java 17 |
| ORM | MyBatis-Plus |
| 数据库 | PostgreSQL 15 |
| 向量检索 | pgvector |
| 关键词检索 | Elasticsearch 8.x |
| 消息队列 | RocketMQ 5.x |
| 文档解析 | Apache Tika, PDFBox |
| Embedding / Query Rewrite / Rerank | DashScope API |
| 前端 | Vue 3, Vite |
| 部署 | Docker Compose, Nginx |

## 项目结构

```text
rag-forge/
├── backend/                 # Spring Boot 后端服务
├── frontend/                # Vue 3 前端
├── reranker/                # 可选 Python Reranker 服务预留
├── docker/                  # 中间件配置
├── docs/                    # 架构、路线图、测试计划
├── docker-compose.yml           # 本地中间件环境
├── docker-compose-data.yml      # Server 1 数据与检索层
├── docker-compose-ingress.yml   # Server 2 入口层（Nginx + 前端）
├── docker-compose-backend.yml   # Server 3 应用层（RAGForge 后端）
├── docker-compose-app.yml       # LEGACY 单机模式（Nginx + 后端同机）
├── deploy.sh                    # 三层部署脚本
└── nginx.conf                   # Nginx 前端和 API 代理配置
```

## 页面功能

- Dashboard：知识库、文档、分块和最近活动概览。
- Knowledge Base：创建知识库、上传和管理文档。
- Document Detail：查看文档解析状态和分块结果。
- Debug Console：调试检索策略并查看返回 Chunk。
- Evaluation Lab：管理评测集并分析检索质量。
- API Gateway：管理 API Key 和外部调用。
- Performance Probe：手动诊断检索链路耗时。

## 环境要求

- JDK 17+
- Maven 3.8+
- Node.js 18+
- Docker 和 Docker Compose
- DashScope API Key

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
```

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

Vite 开发服务会把 `/api/v1` 代理到 `http://localhost:8080`。

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

检索接口：

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-ragforge-dev" \
  -d '{
    "query": "候选人有哪些 Java 项目经验？",
    "strategy": "full",
    "topK": 5
  }'
```

说明：

- 系统启动后如果数据库里还没有任何 API Key，会临时放行 API 请求。
- 一旦创建了 API Key，受保护接口需要携带 `X-API-Key`。
- 开发环境内置 `sk-ragforge-dev`，便于本地调试。

## 部署说明

生产环境采用**三层架构**（详见 [docs/deployment-three-tier.md](docs/deployment-three-tier.md)）：

| 服务器 | 角色 | 组件 |
| --- | --- | --- |
| Server 1（172.25.90.183） | 数据层 | PostgreSQL、pgvector、Elasticsearch、Redis、RocketMQ（Reranker 预留，当前不默认启动） |
| Server 2（8.163.63.222） | 入口层 | Nginx、RAGForge 前端、CareerMate 前端 |
| Server 3（8.138.191.228） | 应用层 | RAGForge backend、CareerMate backend、爬虫 |

Compose 文件：

- `docker-compose-data.yml` → Server 1（保持不动）
- `docker-compose-ingress.yml` → Server 2
- `docker-compose-backend.yml` → Server 3
- `docker-compose-app.yml` → **LEGACY** 单机模式（兼容旧部署）

迁移与切流步骤见 [docs/deployment-migration-runbook.md](docs/deployment-migration-runbook.md)。

部署注意事项：

- **Server 1 数据层不动**；**Server 2 只跑 Nginx + 两个前端**；**Server 3 跑 RAGForge backend (:8080) 和 CareerMate backend (:18080)**。
- RAGForge 与 CareerMate 前端共用 Nginx html 根目录：`/opt/rag-forge/frontend/dist/`。CareerMate 前端在子目录 `careermate/`，由 CareerMate CI 单独同步；`deploy.sh` 同步 RAGForge 前端时会 `--exclude careermate/`，不会删除 CareerMate 静态资源。
- Server 3 首次部署前创建 `/opt/rag-forge/backend/target`；`Dockerfile` 位于 `/opt/rag-forge/backend/Dockerfile`。
- Server 3 可在 `/opt/rag-forge/docker-compose.override.yml` 注入真实 API Key、数据库密码等敏感配置（**服务器本地文件，不入库**）；`deploy.sh` 检测到该文件时自动叠加使用。

当前版本更适合作为中小型知识库检索服务验证。一个比较务实的容量目标是：

```text
10,000 份文档 / 50,000 到 80,000 个 Chunk
```

实际容量取决于文档长度、切分参数、Embedding 调用速度、ES/PG 参数和服务器资源。

## 文档

- [三层部署架构](docs/deployment-three-tier.md)
- [迁移 Runbook](docs/deployment-migration-runbook.md)
- [架构设计](docs/architecture.md)
- [当前真实架构与重构路线](docs/current-architecture-and-refactor-roadmap.md)
- [任务记录](docs/tasks.md)
- [测试计划](docs/ragforge-test-plan.html)
- [ECS 性能测试报告](docs/ecs-performance-test-report-after-optimization-20260601.html)

## 当前状态

RAGForge 已完成阶段性验收，核心的导入、索引、检索、调试、评测、API Key 管理和线上部署流程已经跑通。

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
- 完成 ECS 线上压测与优化前后对比报告。

仍建议后续继续完善：

- 将本地文件存储切换为 OSS / MinIO / NAS，便于后端多实例部署。
- API 实例、文档处理 Worker、维护任务实例做角色隔离。
- 为 `full` 策略增加异步化、缓存或更明确的队列化能力。
- 引入 Prometheus/Grafana 或云监控告警，长期观察 PG、ES、JVM 和检索分段耗时。
- 评测集增加人工标注与 MRR/Top1 等更严格指标。
- 生产数据库迁移建议接入 Flyway 或 Liquibase，避免手动 SQL 遗漏。

## 开源说明

这个项目适合作为 RAG 检索系统、混合检索、工程化文档处理和评测闭环的学习与实践项目。

如果要正式开源发布，建议补充：

- `LICENSE`
- 示例截图
- 更完整的 API 文档
- 一份可复现的 demo 数据集
