# 01 - 需求说明

## 项目整体需求

构建一个企业级 RAG 知识库实验平台，按版本逐步演进，覆盖：

1. 文档导入与分块
2. 向量 Embedding 与 Naive RAG 问答
3. 真实模型接入（Embedding / Chat）
4. RAG 链路可观察与 Debug
5. 关键词检索（BM25）
6. 混合检索（Hybrid Search，当前）
7. （规划）重排、评测、工程化

每个版本须可独立运行、可测试、可演示，用于学习与面试展示。

---

## 已完成版本需求

### V0：项目骨架版

**状态：** 已完成

**目标：**

- 可运行的前后端骨架
- 健康检查、统一响应、全局异常
- 基础布局与 Dashboard
- PostgreSQL 数据源配置（无业务表）

**不做：** 业务表、文档、RAG、检索

---

### V1：文档导入与分块版

**状态：** 已完成

**目标：**

- 知识库 CRUD
- Markdown/TXT 上传、解析、固定大小分块
- 样例数据一键初始化
- 文档列表、Chunk 查看、Dashboard 统计

**允许：** 知识库、文档、Chunk、样例数据、Dashboard

**禁止：** Embedding、向量检索、LLM、Chat

---

### V2：Naive RAG 问答版

**状态：** 已完成

**目标：**

- Chunk 向量化（PgVector）
- 向量 TopK 检索
- Chat 问答、Prompt 组装、引用来源
- 会话与消息记录

**允许：** Embedding、PgVector、向量检索、Chat、Prompt、引用来源

**禁止：** BM25、Elasticsearch、Hybrid、Reranker、Debug Console、Evaluation、权限、多轮对话

---

### V2.5：真实模型接入版

**状态：** 已完成

**目标：**

- Qwen Embedding（DashScope）
- DeepSeek Chat
- Mock / 真实 Provider 配置切换
- 切换 Embedding 后向量一致性校验与重建

**在 V2 基础上扩展，不改变 V2 核心流程边界。**

---

### V3：RAG Debug 可观察版

**状态：** 已完成

**目标：**

- 用户在前端看到一次 RAG 问答的完整内部过程
- 召回 Chunk、Context 过滤、Prompt、Answer、耗时
- Context 过滤（`minScore`、`maxScoreGap`、`maxChunks`）
- 查询历史与详情

**允许：** Debug 查询、召回/Context/Prompt/Answer 展示、Context 过滤、查询历史、耗时统计

**禁止：** BM25、Elasticsearch、Hybrid、Reranker、Evaluation（V4 起 BM25 单独实现）

---

### V4：关键词检索版

**状态：** 已完成

**目标：**

- 引入 Elasticsearch + BM25
- 解决错误码、接口路径、专有名词等场景下纯向量检索不稳定的问题
- Debug 页支持 `VECTOR` / `BM25` 检索模式切换
- BM25 模式下仍走 V3 Context 过滤 → Prompt → 回答
- `terms` 专有词字段与 BM25 加权查询；「SMS_429 是什么意思？」Top1 排序问题已修复

**允许：**

- Elasticsearch（本地 docker-compose 或远程，以 `.env` 为准）
- `POST /api/search/index/rebuild` 全量同步 ES
- `POST /api/search/bm25` 关键词检索
- 索引 `rag_document_chunk`，字段含 `terms` 专有词
- Debug `searchMode`：`VECTOR` / `BM25`

**禁止（V4 边界，V5 另行规划）：**

- Hybrid Search、Reranker、Query Rewrite、Evaluation
- 权限、多轮对话

---

## 当前正在开发版本

### V5：Hybrid Search（混合检索版）

**状态：** 当前 / 启动中

**目标：**

- 融合 **Vector + BM25** 两路召回结果（应用层融合排序，如 RRF）
- 改善单一检索方式在部分问句上不稳定的问题
- 复用现有 **Debug** 链路，展示 Hybrid 检索过程（`searchMode=HYBRID`）
- 融合后仍经 `ContextChunkFilter` → `PromptBuilder` → Chat

**允许：**

- 复用 `VectorRetrievalService`、`Bm25SearchService`
- 新增应用层 Hybrid 融合服务与编排（不落库）
- Debug / 可选独立 API 支持 HYBRID 模式（具体接口在 V5 任务中定义）

**不做：**

- Reranker
- Evaluation
- Query Rewrite
- 权限
- 多租户
- 多轮对话
- V6+ 空类、空接口、空页面、空表

---

## 后续版本规划

### V6：Reranker（重排）

- 粗召回后精排
- **未开始**

### V7：评测中心（Evaluation）

- 测试用例、批量评测
- Recall@K、MRR 等指标
- **未开始**

### V8：工程化增强

- 权限、多租户、多轮对话
- 监控、部署、生产级能力
- **未开始**

---

## 版本路线图摘要

| 版本 | 名称 | 状态 |
|------|------|------|
| V0 | 项目骨架版 | 已完成 |
| V1 | 文档导入与分块版 | 已完成 |
| V2 | Naive RAG 问答版 | 已完成 |
| V2.5 | 真实模型接入版 | 已完成 |
| V3 | RAG Debug 可观察版 | 已完成 |
| V4 | 关键词检索版 | 已完成 |
| V5 | Hybrid Search | **当前 / 启动中** |
| V6 | Reranker | 未开始 |
| V7 | 评测中心 | 未开始 |
| V8 | 工程化增强 | 未开始 |
