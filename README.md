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
├── docker-compose.yml       # 本地中间件环境
├── docker-compose-data.yml  # 数据与检索层部署
├── docker-compose-app.yml   # 应用入口层部署
├── deploy.sh                # 简单部署脚本
└── nginx.conf               # Nginx 前端和 API 代理配置
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

仓库中保留了一个轻量两层部署方案：

- `docker-compose-data.yml`：PostgreSQL、pgvector、Elasticsearch、Redis、RocketMQ
- `docker-compose-app.yml`：RAGForge 后端服务和 Nginx 前端入口

参考部署规格：

| 服务器 | 角色 | 组件 |
| --- | --- | --- |
| 4 vCPU / 8 GiB | 数据与检索层 | PostgreSQL、pgvector、Elasticsearch、Redis、RocketMQ |
| 2 vCPU / 4 GiB | 应用入口层 | Nginx、Vue 前端、RAGForge 后端 |

当前版本更适合作为中小型知识库检索服务验证。一个比较务实的容量目标是：

```text
10,000 份文档 / 50,000 到 80,000 个 Chunk
```

实际容量取决于文档长度、切分参数、Embedding 调用速度、ES/PG 参数和服务器资源。

## 文档

- [架构设计](docs/architecture.md)
- [当前真实架构与重构路线](docs/current-architecture-and-refactor-roadmap.md)
- [任务记录](docs/tasks.md)
- [测试计划](docs/ragforge-test-plan.html)

## 当前状态

RAGForge 仍处于持续开发阶段，核心的导入、索引、检索、调试、评测和 API 管理流程已经具备，但还有一些工程化方向需要继续完善：

- 抽出统一的 `RetrievalService`，避免 Controller、评测和诊断页各自维护检索链路。
- 进一步提升评测集标注、失败样本分析和结果可信度。
- 大批量导入时增加批量写入、限流、重试和补偿机制。
- 完善系统监控、指标采集和生产环境告警。
- 补充更系统的单元测试、集成测试和压测脚本。

## 开源说明

这个项目适合作为 RAG 检索系统、混合检索、工程化文档处理和评测闭环的学习与实践项目。

如果要正式开源发布，建议补充：

- `LICENSE`
- 示例截图
- 更完整的 API 文档
- 一份可复现的 demo 数据集
