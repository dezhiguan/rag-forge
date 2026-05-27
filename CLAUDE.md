# RAGForge - AI 开发上下文恢复指南

> 每次新会话开始，Claude Code 会自动加载本文件。
> 本文件是项目的"记忆锚点"，保持精简，详细内容链接到 docs/ 目录。

---

## 项目身份

- **项目名称**：RAGForge - 企业级 RAG 知识引擎
- **项目定位**：RAG 基础设施层，为 CareerMate（AI 求职 Agent）提供知识检索 API
- **技术栈**：Java 17 + Spring Boot 3.2 + PostgreSQL/pgvector + Elasticsearch + RocketMQ
- **前端**：Vue 3 + Vite（6 个管理后台页面）
- **目标部署**：阿里云 ECS
- **目的**：简历展示 + 面试演示

## 项目位置

`/Users/amy/CursorProject/rag-forge/`

## 架构师

@guandezhi，Java 技术栈，该项目从 0 到 1 开发。

## 开发模式

- **架构师**（本会话）负责：架构设计、需求分析、数据库设计、API 设计、任务拆分、Cursor prompt 编写
- **Cursor** 负责：具体代码实现，按照 prompt 逐个任务执行
- **本仓库不直接写代码**，只维护架构文档和任务 prompt

## 项目结构

```
rag-forge/
├── CLAUDE.md              ← 本文件（自动加载）
├── docs/
│   ├── architecture.md    ← 完整架构设计文档（权威参考）
│   └── tasks.md           ← 任务追踪（当前进度、状态）
├── backend/               ← Spring Boot（Cursor 生成）
├── reranker/               ← Python 微服务（Cursor 生成）
├── frontend/              ← Vue 3 前端（已有 6 页面 mockup）
└── docker-compose.yml     ← 部署编排（Cursor 生成）
```

## 恢复上下文的步骤

### 新会话开始时，按以下顺序加载：

1. **先读本文件**（自动加载）
2. **读 docs/architecture.md** —— 完整架构，所有技术决策都在里面
3. **读 docs/tasks.md** —— 当前进度，哪些完成了、哪些在做、哪些没开始
4. **用 git log / git diff** —— 看最近实际改了什么代码

### 快速恢复命令

```
读一下 docs/architecture.md 和 docs/tasks.md，然后告诉我当前项目状态
```

## 核心架构决策（速查）

### 3 个模型
| 模型 | 用途 | 调用方 |
|------|------|--------|
| text-embedding-v4 (DashScope) | 文档块→1024维向量 | 异步管道 Embedder |
| DeepSeek-V3 | Query改写 + 调试台 + 评测 | Java HTTP 调用 |
| bge-reranker-v2-m3 | 召回精排 | Python 微服务 → Java HTTP 调用 |

### 中间件
- PostgreSQL 15 + pgvector：业务数据 + 向量存储
- Elasticsearch 8.x + ik分词器：BM25 关键词检索
- RocketMQ 5.x：文档处理异步管道
- 文件存储：本地磁盘 /data/files/（Docker Volume）

### 完整检索链路
```
Query改写(DeepSeek) → 双路召回(pgvector + ES BM25) → RRF融合 → Reranker精排 → 返回TopK
```

### 文档处理管道（异步）
```
上传 → 存磁盘 → RocketMQ → Consumer: 解析(Tika) → 分块 → Embedding → PG入库 → ES索引 → 完成
```

### 数据库：9 张表
knowledge_bases, documents, document_chunks(含vector(1024)), retrieval_logs, eval_datasets, eval_questions, eval_experiments, eval_results, api_keys

### API 接口
知识库 CRUD、文档上传/管理、POST /api/v1/search（核心检索）、评测数据集/实验、系统指标

### 前端：6 个页面
驾驶舱(/)、知识库管理(/knowledge)、文档详情(/document/:id)、检索调试台(/debug)、评测实验室(/eval)、API网关(/api)

## 当前开发阶段

**架构设计阶段** —— Cursor 代码开发尚未开始。

下一步：架构师正在编写 18 个 Cursor 开发 prompt，完成后逐个交给 Cursor 实现。

## 协作规则

1. 每次开发前，先读 `docs/tasks.md` 确认当前进度
2. 每完成一个任务，更新 `docs/tasks.md` 中的状态
3. 遇到架构问题，参考 `docs/architecture.md`，不要偏离原始设计
4. 重大架构变更必须更新 `docs/architecture.md` 和本文件
5. 每次提交代码时，commit message 中标注对应的任务编号
