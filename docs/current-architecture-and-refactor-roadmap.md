# RAGForge 当前真实架构与重构路线

> 日期：2026-05-30  
> 视角：架构体检 / 当前实现校准 / 后续重构路线  
> 说明：本文不替代 `docs/architecture.md` 的历史设计价值，但以后讨论系统现状、问题和重构优先级时，应优先参考本文。

---

## 1. 当前定位

RAGForge 当前应被定位为 **RAG 检索基础设施服务**，不是聊天应用。

它的核心产物是：

```text
query -> chunks + scores + metadata + latency
```

而不是：

```text
query -> final answer
```

项目二 CareerMate 或其他上层 Agent 可以调用 RAGForge 的检索接口，拿到候选上下文后再由 Agent/LLM 生成最终回答。

### 建议统一口径

- RAGForge 负责文档处理、索引、检索、调试、评测和 API 输出。
- RAGForge 的 Chat LLM 能力只用于调试、Query 改写、评测辅助，不是对外核心问答能力。
- 上层 Agent 负责任务规划、工具调用、对话记忆和最终回答生成。

---

## 2. 当前真实技术栈

| 层级 | 当前实现 | 架构判断 |
|---|---|---|
| 后端框架 | Spring Boot 3.2 + Java 17 | 合理，适合企业服务 |
| ORM | MyBatis-Plus | 可接受，开发效率高 |
| 业务库/向量库 | PostgreSQL + pgvector | 当前规模合理 |
| 关键词检索 | Elasticsearch 8.x | 合理，但在 4C8G 上资源压力较大 |
| 消息队列 | RocketMQ | 对简历项目偏重，但能展示异步处理能力 |
| 文档解析 | Apache Tika / PDFBox | 合理 |
| Embedding | DashScope text-embedding-v4 | 合理，1024 维 |
| Query Rewrite | 当前走 DashScope/Qwen 配置 | 文档中 DeepSeek 口径需要更新 |
| Reranker | 当前 Java 调 DashScope rerank API | 文档中 Python bge-reranker 口径需要更新 |
| 前端 | Vue 3 + Vite | 合理 |
| 部署 | 4C8G 数据层 + 2C4G 应用层 | v1 可用，但要承认资源边界 |

### Reranker 口径

原架构文档写的是：

```text
bge-reranker-v2-m3 Python 微服务
```

当前实际 Java 代码走的是 DashScope rerank API。两者都可以，但不能混着讲。

建议当前版本统一为：

```text
当前版本使用 DashScope rerank API；Python bge-reranker 作为后续私有化扩展预留。
```

---

## 3. 当前核心链路

### 3.1 文档导入链路

```text
上传文件
-> 本地落盘
-> documents 表 pending
-> RocketMQ 消息
-> Tika 解析
-> 固定窗口切分
-> DashScope embedding
-> document_chunks 写入 PG/pgvector
-> ES bulk index
-> documents completed/failed
```

当前优点：

- 异步处理方向正确。
- PG + ES 双索引已具备。
- 文件存储接口化，后续可切 OSS。
- 已补充 pipeline 分段耗时日志。

当前问题：

| 问题 | 影响 | 建议 |
|---|---|---|
| 分块是字符滑窗，不是语义分块 | 文档里不能说“语义分块” | 先统一叫固定窗口切分 |
| PG chunk 逐条 insert | 万级导入慢 | 后续改 batch insert |
| ES 写入失败只 warn | PG/ES 可能不一致 | 增加索引状态或补偿任务 |
| doc_count/chunk_count 手动加减 | 并发下可能不准 | 改 SQL 原子更新或增加校准任务 |
| 缺少 embedding 限流 | 大批量导入可能触发限流 | 增加限流/退避/队列水位控制 |

### 3.2 检索链路

当前建议固定策略语义：

| strategy | 实际语义 |
|---|---|
| keyword | Elasticsearch BM25 |
| vector | query embedding + pgvector |
| hybrid | vector + keyword + RRF |
| rewrite | query rewrite + multi-vector |
| full | query rewrite + hybrid + rerank |

当前问题：

- 检索逻辑散在 `SearchController` 和 `EvalExperimentServiceImpl`，容易出现策略不一致。
- 之前评测 `full` 和调试台 `full` 不一致，已修正。
- `rewrite` 当前是改写后多路 vector，不是改写后 hybrid，需要文档讲清楚。
- RRF 参数、recallTopK 等关键参数还没有配置化。

建议后续抽出统一服务：

```text
RetrievalService / SearchEngine
```

由 Controller、评测实验室、性能诊断页统一调用，避免多处复制策略逻辑。

---

## 4. 当前部署现实

当前部署规划：

| 服务器 | 规格 | 角色 | 组件 |
|---|---|---|---|
| ECS 云服务器 | 4 vCPU / 8 GiB / 40 GiB | 数据与检索层 | PostgreSQL、pgvector、Elasticsearch、Redis、RocketMQ |
| 轻量服务器 | 2 vCPU / 4 GiB / 50 GiB | 应用入口层 | Nginx、Vue 前端、RAG Java 后端 |

建议容量口径：

```text
当前版本目标容量：10,000 文档 / 50,000 到 80,000 chunk
```

不建议当前口径宣称百万级 chunk 已验证。可以说：

```text
pgvector 在百万级 chunk 内有演进空间，但当前部署规格下先以 5-8 万 chunk 为验收目标。
```

---

## 5. 页面与产品边界

当前页面结构合理：

```text
Dashboard
KnowledgeBase
DocumentDetail
DebugConsole
EvaluationLab
ApiGateway
PerformanceProbe
```

### 保留建议

- `DebugConsole` 是核心展示页，应继续打磨。
- `EvaluationLab` 是质量可信度的关键，但要严肃化。
- `PerformanceProbe` 可以长期保留，但要作为管理员工具，避免普通用户误用。

### 文案边界

避免让用户误解 RAGForge 是聊天产品。建议所有页面保持这个边界：

- 检索调试台可以展示 Prompt 预览和模拟 LLM 输出。
- 对外 API 默认返回 chunks，不默认生成答案。
- LLM 生成能力属于调试/评测辅助能力。

---

## 6. 评测实验室体检

评测实验室是当前最需要严肃化的模块。

已发现并开始修复的问题：

| 问题 | 状态 |
|---|---|
| 评测 `full` 与检索调试台 `full` 链路不一致 | 已修正 |
| 失败原因分类过粗 | 已初步修正 |
| 优化建议前端硬编码 | 已改为优先使用后端建议 |
| 失败样本缺少 rank 上下文 | 已增加 expectedBestRank 等字段 |

仍需后续改进：

- 快速体验自动把 Top3 当标准答案，只能作为自动弱标注。
- 失败样本应能展示 expected chunk 和 recalled chunk 的文本内容。
- 评测题应该支持人工修正标准 Chunk。
- 应保存每条召回结果的 score detail，而不是只保存 chunkId。

### 推荐评测模型

```text
人工标注 expected_chunk_ids
-> 运行策略
-> 保存 recalled_chunk_ids + rank + score
-> 计算 Top1 / Top3 / MRR
-> 基于 expected rank 分类失败
```

失败分类建议：

| 类型 | 判断 |
|---|---|
| 标注缺失 | expected_chunk_ids 为空 |
| 无召回结果 | recalled_chunk_ids 为空 |
| 召回不足 | expected 未进入 TopK |
| 排序不足 | expected 进入 TopK 但未进 Top3 |
| Top1 排序不足 | expected 进入 Top3 但不在第 1 位 |

---

## 7. 当前最大架构问题排序

按优先级：

1. 架构文档与当前实现不一致。
2. Reranker 方案口径不一致。
3. 检索策略逻辑没有抽到统一服务。
4. 评测实验室仍有演示型弱标注问题。
5. 文档处理 pipeline 缺少写入补偿和数据校准。
6. 缺少标准化监控指标。
7. API Gateway 页面可能与真实接口不一致。
8. 测试覆盖不足以支撑后续重构。

---

## 8. 重构路线

### 阶段 1：统一口径，不推倒重来

目标：让文档、页面、API、代码语义一致。

任务：

- 更新架构文档为当前真实版本。
- 明确 RAGForge 不负责最终对话生成。
- 明确当前 Reranker 使用 DashScope，Python bge 是预留扩展。
- 明确策略语义：keyword / vector / hybrid / rewrite / full。
- 明确当前部署规格和容量目标。
- 更新 API Gateway，确保展示真实接口。

### 阶段 2：修评测可信度

目标：让评测结果能支撑真实判断。

任务：

- 标记快速体验数据集为“自动弱标注”。
- 增加 expected/recalled chunk 内容展示。
- 支持人工修正 expected_chunk_ids。
- 将失败原因和建议统一由后端生成。
- 增加标注质量检查。

### 阶段 3：统一检索引擎

目标：避免调试台、API、评测实验室策略不一致。

建议抽象：

```java
RetrievalService.search(SearchCommand command) -> SearchResponse
```

统一处理：

- strategy 解析
- query rewrite
- vector recall
- keyword recall
- RRF fusion
- rerank
- latency breakdown
- retrieval log

### 阶段 4：导入与索引工程化

目标：支撑 10,000 文档稳定导入。

任务：

- document_chunks batch insert。
- embedding 限流和退避。
- ES 写入失败补偿。
- 文档索引状态细化。
- KB/doc/chunk 计数校准任务。
- RocketMQ lag 和失败重试观测。

### 阶段 5：可观测性与运维

目标：线上能定位问题。

任务：

- Spring Actuator。
- Micrometer/Prometheus 指标。
- HTTP latency、JVM、线程池、DB pool。
- pipeline 分段指标。
- search 分段指标。
- ES/PG/RocketMQ 资源水位面板。
- PerformanceProbe 增加管理员权限控制。

---

## 9. 建议保留与删除

### 建议保留

- Spring Boot + PG/pgvector + ES + RocketMQ 主架构。
- Vue 管理后台。
- DebugConsole。
- EvaluationLab，但需要严肃化。
- PerformanceProbe，作为管理员诊断工具。
- FileStorageService 抽象。

### 建议弱化或改口径

- Python bge-reranker 微服务：当前没有真正使用，先作为扩展项。
- DeepSeek-V3：当前 QueryRewrite 配置实际是 DashScope/Qwen，文档要改。
- “语义分块”：当前是固定窗口切分，先不要这么讲。
- “百万级 chunk 已验证”：当前没有验证，不要写。

### 建议后续删除或替换

- 前端硬编码失败建议。
- 自动 Top3 作为真实标准答案的评测口径。
- API Gateway 中与真实接口不一致的文档。

---

## 10. 当前版本推荐面试讲法

```text
RAGForge 是我做的 RAG 检索基础设施服务。
它不负责最终聊天生成，而是负责知识从文档进入系统后，如何被解析、切分、向量化、索引、检索和评测。

当前链路包括：
文档上传后通过 RocketMQ 异步处理，Tika 解析，固定窗口切分，DashScope embedding，
写入 PostgreSQL/pgvector 和 Elasticsearch。

检索侧支持 keyword、vector、hybrid、rewrite、full 五种策略。
full 是 Query 改写 + 混合召回 + Rerank。

我还做了检索调试台和评测实验室，用于观察每一步的分数、耗时和失败样本。
后续我会把检索逻辑抽成统一 RetrievalService，并加强评测标注、批量导入和线上可观测性。
```

---

## 11. 下一步建议

优先顺序：

1. 更新 `docs/architecture.md`，让它和当前实现一致。
2. 重构 `SearchController` 与 `EvalExperimentServiceImpl` 的重复检索逻辑。
3. 优化评测实验室的 expected/recalled chunk 展示。
4. 修正 API Gateway 页面。
5. 加 Actuator/Micrometer。
6. 做 document_chunks batch insert 和 embedding 限流。

