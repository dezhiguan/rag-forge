# 04 - 数据库设计

## 概述

- **关系型数据库**：PostgreSQL（元数据、会话、Debug 日志、向量表）。
- **向量扩展**：PgVector（`CREATE EXTENSION IF NOT EXISTS vector`）。
- **搜索引擎**：Elasticsearch 8.x（V4 起，**不新增**关系型表存储检索副本）。

Schema 定义见：`backend/src/main/resources/db/schema.sql`

---

## V1 表

### knowledge_base

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL PK | 主键 |
| name | VARCHAR(100) | 名称 |
| description | VARCHAR(500) | 描述 |
| status | VARCHAR(30) | 状态 |
| created_at / updated_at | TIMESTAMP | 时间戳 |
| deleted | SMALLINT | 逻辑删除 |

### document

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL PK | 主键 |
| kb_id | BIGINT | 所属知识库 |
| file_name | VARCHAR(255) | 文件名 |
| file_type | VARCHAR(30) | 类型 |
| file_size | BIGINT | 大小 |
| storage_path | VARCHAR(500) | 存储路径 |
| content_hash | VARCHAR(64) | 内容哈希 |
| status | VARCHAR(30) | 处理状态 |
| error_message | TEXT | 错误信息 |
| created_at / updated_at | TIMESTAMP | |
| deleted | SMALLINT | |

索引：`idx_document_kb_id`

### document_chunk

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL PK | |
| kb_id | BIGINT | 知识库 |
| document_id | BIGINT | 文档 |
| chunk_index | INT | 块序号 |
| title_path | VARCHAR(500) | 标题路径 |
| content | TEXT | 正文 |
| token_count | INT | |
| content_hash | VARCHAR(64) | |
| created_at / updated_at | TIMESTAMP | |
| deleted | SMALLINT | |

索引：`idx_document_chunk_document_id`、`idx_document_chunk_kb_id`

---

## V2 表

### chunk_embedding

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL PK | |
| kb_id | BIGINT | |
| document_id | BIGINT | |
| chunk_id | BIGINT | 关联 document_chunk，唯一 |
| embedding_model | VARCHAR(100) | 模型名 |
| embedding_dimension | INT | 维度 |
| embedding | vector | PgVector 向量 |
| created_at / updated_at | TIMESTAMP | |

索引：`uk_chunk_embedding_chunk_id`、`idx_chunk_embedding_kb_id`

### chat_session

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL PK | |
| kb_id | BIGINT | |
| title | VARCHAR(200) | |
| created_at / updated_at | TIMESTAMP | |
| deleted | SMALLINT | |

索引：`idx_chat_session_kb_id`

### chat_message

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL PK | |
| session_id | BIGINT | |
| kb_id | BIGINT | |
| role | VARCHAR(30) | user / assistant |
| content | TEXT | |
| source_chunks | TEXT | 引用 JSON |
| prompt_tokens / completion_tokens | INT | |
| latency_ms | BIGINT | |
| created_at | TIMESTAMP | |
| deleted | SMALLINT | |

索引：`idx_chat_message_session_id`

---

## V3 表

### rag_query_log

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL PK | queryLogId |
| kb_id | BIGINT | |
| question | TEXT | 用户问题 |
| prompt / context / answer | TEXT | Debug 快照 |
| top_k | INT | |
| embedding_provider / embedding_model | VARCHAR | |
| chat_provider / chat_model | VARCHAR | |
| retrieval_time_ms / generation_time_ms / total_time_ms | BIGINT | 耗时 |
| search_mode | VARCHAR(20) | V4：`VECTOR` / `BM25`；V5：`HYBRID` |
| enable_rerank | SMALLINT | V6：是否启用轻量 Reranker（0/1） |
| question_tokens / context_tokens / system_prompt_tokens / answer_tokens | INT | V11：Chat 分项 Token |
| input_tokens / output_tokens / total_tokens | INT | V11：Chat 输入输出与总 Token（含 Embedding） |
| estimated_cost | NUMERIC(12,6) | V11：Chat 分项费用（元） |
| embedding_tokens | BIGINT | V11-02：Embedding Token（默认 0） |
| embedding_cost | NUMERIC(18,8) | V11-02：Embedding 费用（元） |
| total_cost | NUMERIC(18,8) | V11-02：Embedding + Chat 总费用（元） |
| price_configured | SMALLINT | V11：是否已配置单价 |
| created_at | TIMESTAMP | |
| deleted | SMALLINT | |

索引：`idx_rag_query_log_kb_id`、`idx_rag_query_log_created_at`

### rag_retrieval_log

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL PK | |
| query_log_id | BIGINT | 关联 query |
| kb_id | BIGINT | |
| document_id | BIGINT | |
| document_name | VARCHAR(255) | |
| chunk_id | BIGINT | |
| chunk_index | INT | |
| score | DOUBLE PRECISION | |
| content | TEXT | |
| rank_position | INT | |
| used_in_prompt | SMALLINT | V3：是否进入 Prompt |
| filter_reason | VARCHAR(50) | V3：过滤原因 |
| original_rank | INT | V6：检索原始排名（可空） |
| rerank_rank | INT | V6：重排后排名（可空） |
| rerank_score | DOUBLE PRECISION | V6：重排分（可空） |
| created_at | TIMESTAMP | |

索引：`idx_rag_retrieval_log_query_log_id`

---

## V4：Elasticsearch 索引（非关系型表）

**V4 不新增 PostgreSQL 业务表。** Chunk 检索副本存于 ES 索引。

### 索引名

默认：`rag_document_chunk`（配置项 `rag.elasticsearch.index`）

### 字段 mapping

| 字段 | ES 类型 | 说明 |
|------|---------|------|
| kbId | long | 知识库 ID（filter） |
| documentId | long | 文档 ID |
| documentName | keyword | 文档名 |
| chunkId | long | Chunk ID |
| chunkIndex | integer | 块序号 |
| content | text | 全文（standard analyzer） |
| content.keyword | keyword | 精确子字段 |
| terms | keyword | 索引时从 Chunk 抽取的专有词列表 |

### 专有词抽取规则（`SearchTermExtractor`）

| 类型 | 规则示例 |
|------|----------|
| 错误码 | `\b[A-Z]{2,}_[0-9]{3,}\b`（如 SMS_429） |
| API 路径 | `/api/[A-Za-z0-9/_-]+` |
| API 短名 | 路径末段；查询侧额外匹配 kebab-case |

重建索引时：从 `document_chunk` 读取 → 计算 `terms` → bulk 写入 ES。

---

## 版本与存储对照

| 版本 | PostgreSQL 表 | ES 索引 |
|------|---------------|---------|
| V0 | 无业务表 | - |
| V1 | knowledge_base, document, document_chunk | - |
| V2 | + chunk_embedding, chat_session, chat_message | - |
| V3 | + rag_query_log, rag_retrieval_log | - |
| V4 | 无新增表；query_log 增 search_mode | rag_document_chunk |
| V5 | **无新增表、无新增索引** | 复用上述全部存储 |

---

## V5：Hybrid Search 存储设计（当前版本）

V5 **不新增** PostgreSQL 表，**不新增** Elasticsearch 索引。融合与排序在应用层完成，结果仅在单次查询链路中存在。

### 复用对象

| 类型 | 对象 | 用途 |
|------|------|------|
| PostgreSQL | `document_chunk` | Chunk 元数据与正文 |
| PostgreSQL | `chunk_embedding` | 向量召回 |
| PostgreSQL | `rag_query_log` | Debug 查询快照（含 `search_mode`） |
| PostgreSQL | `rag_retrieval_log` | 召回明细（融合后 score 写入日志，非独立融合表） |
| Elasticsearch | `rag_document_chunk` | BM25 关键词召回 |

### 明确不做

- 不建 `hybrid_fusion_result` 等融合结果表
- 不建第二套 ES 索引
- Hybrid 融合中间结果**不落库**（仅 Debug 链路内展示与可选日志字段）

### V6+ 存储原则（未建）

- **V6 Reranker**：不提前建 rerank 分数表。
- **V7 Evaluation**：评测数据集可放文件或后续专用表，**当前不建**。
- **V8**：权限、租户等表待 V8 需求明确后再设计。
