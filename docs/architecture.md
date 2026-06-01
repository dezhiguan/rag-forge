# RAGForge 架构设计文档

> 当前真实版本 | 2026-06-01
>
> 说明：本文描述的是当前代码与部署的真实口径，不再沿用早期“架构设想版”的表述。  
> 更细的重构路线见 [current-architecture-and-refactor-roadmap.md](./current-architecture-and-refactor-roadmap.md)。

## 0. 结论先行

RAGForge 是一个 **RAG 检索基础设施服务**，不是聊天应用。

它的核心输出是：

```text
query -> chunks + scores + metadata + latency
```

而不是：

```text
query -> final answer
```

当前系统的职责边界是：

- RAGForge 负责文档导入、解析、切分、索引、检索、调试、评测和 API 输出。
- RAGForge 的 LLM 能力只用于 Query 改写、调试辅助和评测辅助。
- 最终对话生成应由上层 Agent 或业务系统完成。

## 1. 当前真实技术栈

| 层级 | 当前实现 | 说明 |
| --- | --- | --- |
| 后端框架 | Spring Boot 3.2 + Java 17 | 当前主实现 |
| ORM | MyBatis-Plus | 业务 CRUD 和检索数据访问 |
| 业务库 / 向量库 | PostgreSQL + pgvector | 业务表与向量统一存储 |
| 关键词检索 | Elasticsearch 8.x | BM25 关键词召回 |
| 消息队列 | RocketMQ 5.x | 文档异步处理 |
| 文档解析 | Apache Tika / PDFBox | PDF、Markdown、TXT、Word 等 |
| Embedding | DashScope `text-embedding-v4` | 当前文档向量化主模型 |
| Query Rewrite | DashScope / Qwen 配置 | 当前实际代码口径，不是 DeepSeek |
| Reranker | DashScope rerank API | 当前主链路口径，不是 Python bge |
| 前端 | Vue 3 + Vite | 管理后台与调试台 |
| 部署 | Docker Compose + Nginx | 双服务器部署为主 |

### 1.1 需要特别说明的口径

原始架构文档里提到的 `DeepSeek-V3` 和 `bge-reranker-v2-m3 Python 微服务`，不应再作为当前版本主口径。

当前应统一为：

- Query 改写：DashScope / Qwen 配置
- Rerank：DashScope rerank API
- Python `bge-reranker` 目录保留为后续私有化扩展项

## 2. 当前核心链路

### 2.1 文档导入链路

```text
上传文件
-> 本地落盘
-> documents 表 pending
-> RocketMQ 发送处理消息
-> Tika 解析
-> 固定窗口切分
-> DashScope embedding
-> document_chunks 写入 PG/pgvector
-> ES bulk index
-> documents completed / failed
```

当前实现的要点：

- 文档切分是固定窗口切分，不是语义分块。
- 文档处理是异步链路，避免用户同步等待完整解析和索引。
- PG 与 ES 双写已存在，但仍需继续增强失败补偿和一致性校准。
- 导入链路已经补了分段耗时日志，便于定位瓶颈。

### 2.2 检索链路

当前系统支持的检索策略定义如下：

| strategy | 实际语义 |
| --- | --- |
| `keyword` | Elasticsearch BM25 关键词检索 |
| `vector` | query embedding + pgvector 向量检索 |
| `hybrid` | vector + keyword + RRF 融合 |
| `rewrite` | query rewrite + 多路向量召回 |
| `full` | query rewrite + hybrid + rerank |

需要明确两点：

- `rewrite` 不是 `hybrid`，它目前是改写后多路向量召回。
- `full` 是当前最完整链路，包含 Query 改写、混合召回和 rerank。
- 检索入口已统一到 `RetrievalService`，搜索接口、评测和性能诊断页共享同一套策略实现。
- `vector`、`hybrid`、`full` 已加入策略级限流和服务端超时保护，`full` 默认限制并发 1。
- `hybrid` 的 keyword/vector 召回已使用受控检索线程池并行执行。
- Query embedding、Dashboard 和知识库列表已加入短 TTL 本地缓存。

### 2.3 当前检索响应字段

搜索接口当前会返回：

- `results`
- `strategy`
- `rewrittenQueries`
- `rewriteLatencyMs`
- `vectorLatencyMs`
- `keywordLatencyMs`
- `rerankLatencyMs`
- `latencyMs`

这使得调试台和性能诊断页可以展示分段耗时，而不只是总耗时。

## 3. 页面与产品边界

当前前端页面有 7 个核心页面：

```text
Dashboard
KnowledgeBase
DocumentDetail
DebugConsole
EvaluationLab
ApiGateway
PerformanceProbe
```

### 3.1 页面定位

- `Dashboard`：整体概览。
- `KnowledgeBase`：知识库和文档管理。
- `DocumentDetail`：单文档解析、切分和索引状态。
- `DebugConsole`：检索策略调试与参数对比。
- `EvaluationLab`：评测集、实验和失败样本分析。
- `ApiGateway`：API Key 管理。
- `PerformanceProbe`：检索链路耗时诊断。

### 3.2 产品边界

RAGForge 不是聊天产品。

所以页面和 API 的表达也要遵守这个边界：

- 对外默认返回检索结果，不默认生成答案。
- LLM 只作为调试、改写和评测辅助。
- 不要把系统包装成通用 Chat UI。

## 4. 部署现实

当前采用两台服务器的部署思路。

| 服务器 | 规格 | 角色 | 组件 |
| --- | --- | --- | --- |
| ECS 云服务器 | 4 vCPU / 8 GiB / 40 GiB | 数据与检索层 | PostgreSQL、pgvector、Elasticsearch、Redis、RocketMQ |
| 轻量级服务器 | 2 vCPU / 4 GiB / 50 GiB | 应用入口层 | Nginx、Vue 前端、RAG Java 后端 |

### 4.1 容量口径

当前比较务实的阶段性目标是：

```text
10,000 份文档 / 50,000 到 80,000 个 chunk
```

这个目标适合作为中小型知识库的第一阶段验收。

截至 2026-06-01，线上已完成约：

```text
8 个知识库 / 9,800 份文档 / 约 96,000 个 chunk
```

压测结论显示：普通读接口可用，`vector` 和 `hybrid` 在优化后有明显提升；`full` 仍是重链路，应保持限流、超时和必要的异步化设计。

在当前资源条件下，不建议直接宣称“百万级 chunk 已验证”。

### 4.2 运行口径

当前部署环境里，ES、PG、RocketMQ 的资源都不算宽裕，所以需要承认资源边界：

- ES 占内存最高，检索峰值要控制。
- pgvector 当前适合中小规模检索，不适合无约束膨胀。
- RocketMQ 用于异步化处理，价值主要在解耦和重试。

## 5. 评测实验室状态

评测实验室已经不是纯演示页面，但还没有达到最终可信度标准。

当前已改进的方向包括：

- 评测 `full` 与调试台 `full` 的链路已经统一。
- 失败原因和建议不再完全依赖前端硬编码。
- 失败样本已经增加 rank 上下文字段。

仍然建议继续完善的点：

- 快速体验数据集应明确标注为“自动弱标注”。
- 失败样本应展示 expected / recalled chunk 内容。
- 支持人工修正标准答案标注。
- 结果保存应包含更完整的 score detail。

推荐评测模型：

```text
人工标注 expected_chunk_ids
-> 运行策略
-> 保存 recalled_chunk_ids + rank + score
-> 计算 Top1 / Top3 / MRR
-> 按 expected rank 分类失败
```

## 6. 当前最大不一致点

当前系统的主要问题已经从“口径不统一”转向“生产工程化继续增强”。

已收敛的点：

1. Query Rewrite 和 Reranker 模型口径已统一为 DashScope / Qwen / DashScope rerank。
2. 调试台、评测实验室、搜索 API 的检索策略已统一到 `RetrievalService`。
3. 检索链路已具备分段耗时、限流、超时和本地缓存。

仍需继续增强的点：

1. 文档导入补偿、批量写入和一致性校准仍可继续工程化。
2. 监控和指标体系还不完整，需要生产告警闭环。
3. 多实例部署前应处理共享文件存储、Worker/API 角色隔离和定时任务单实例执行。
4. `full` 策略仍然较重，适合异步化或队列化。

## 7. 推荐的后续工作方向

详细重构路线请看 [current-architecture-and-refactor-roadmap.md](./current-architecture-and-refactor-roadmap.md)。

这里保留一句最重要的结论：

- 先统一口径。
- 再修评测可信度。
- 然后收敛检索链路。
- 最后做工程化增强。
