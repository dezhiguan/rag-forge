# RAGForge 架构设计文档 V4

> 企业级 RAG 知识引擎 | Java Spring Boot | 为 CareerMate Agent 提供知识检索 API
> 
> 架构师：@guandezhi | 2026-05-27

---

## 一、项目定位

RAGForge 是 **RAG 基础设施层**，不是聊天应用。它为上层 Agent（CareerMate）提供：
- 文档上传 → 异步处理 → 向量+关键词双索引
- 完整检索链路：Query改写 → 向量+关键词双路召回 → RRF融合 → Reranker精排
- 检索质量评测框架
- RESTful API（被 CareerMate 调用）

面试一句话：*"这是一个从文档解析、Query改写、混合召回、Reranker精排到质量评测的完整 RAG 闭环，不是调 LangChain API 的 wrapper。"*

---

## 二、技术选型

### 2.1 技术栈

| 层级 | 方案 | 理由 |
|------|------|------|
| 后端框架 | Spring Boot 3.2 + Java 17 | 企业级标配 |
| ORM | MyBatis-Plus 3.5 | 国内主流，代码生成效率高 |
| 数据库 | PostgreSQL 15 + pgvector 扩展 | 业务数据 + 向量存储，一个库搞定 |
| 关键词检索 | Elasticsearch 8.x + ik 分词器 | 用户已有，BM25 生态成熟 |
| 消息队列 | RocketMQ 5.x | 文档处理异步化，失败可重试 |
| 文档解析 | Apache Tika 2.x + PDFBox | Java 生态，支持 PDF/Word/Markdown |
| Embedding | 阿里云 DashScope text-embedding-v4 | 1024维，中英文优异 |
| Chat / Query改写 | DeepSeek-V3 | 性价比高，中文能力强 |
| Reranker | bge-reranker-v2-m3（Python 微服务） | 开源 SOTA，中文效果好，私有化部署 |
| 文件存储 | 本地磁盘（Docker Volume） | v1 避免引入 OSS，架构预留策略模式切换 |
| 前端 | Vue 3 + Vite（已有 6 页面） | 直接搬进 frontend/ |
| 部署 | Docker Compose | 一键启动全部服务 |

### 2.2 模型使用全景

```
RAGForge 用到 3 个模型：

┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│  text-embedding-v4 (阿里云 DashScope)                           │
│  ├─ 用途：文档块 → 1024维向量                                   │
│  └─ 调用位置：异步管道的 Embedder                               │
│                                                                 │
│  bge-reranker-v2-m3 (自建 Python 微服务)                        │
│  ├─ 用途：召回候选集精排                                        │
│  └─ 调用位置：HybridSearchService → HTTP 调用 Reranker 服务     │
│                                                                 │
│  DeepSeek-V3 (DeepSeek API)                                     │
│  ├─ 用途 ①：Query 改写（核心链路） ✅                           │
│  ├─ 用途 ②：检索调试台结果验证（调试工具） ❌                    │
│  └─ 用途 ③：评测实验室答案可信度判断（评测工具） ❌             │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2.3 关键决策（面试追问应对）

**为什么 PG + ES，不用 Milvus？**
- pgvector 做向量检索在小规模（百万级 chunk）下性能足够
- ES 做关键词是它最擅长的，用户已有不用白不用
- 减少运维成本：PG + ES 两个中间件 vs PG + ES + Milvus 三个

**为什么 Reranker 用 Python 微服务而不是 Java？**
- bge-reranker 生态是 Python 的，用 Java 调会多一层适配损耗
- 独立微服务体现了"多语言协作"的工程能力——面试加分项
- FastAPI 起一个轻量服务，20 行代码搞定，Java 通过 HTTP 调用

**为什么 RocketMQ？**
- 文档处理链路长（解析→分块→向量化→索引），同步等 10-30 秒体验差
- 异步后 Consumer 失败可自动重试，保证文档不丢
- 面试展示"高可用异步处理"能力

**为什么文件存磁盘不存 OSS？**
- v1 聚焦核心能力，本地文件足够
- 架构预留 FileStorageService 接口，切换 OSS 只需新增一个实现类

---

## 三、项目结构

```
rag-forge/
├── backend/
│   ├── src/main/java/com/ragforge/
│   │   ├── RAGForgeApplication.java          # 启动类
│   │   ├── common/                            # 公共层
│   │   │   ├── Result.java                    # 统一响应体 {code, msg, data}
│   │   │   ├── BizException.java              # 业务异常
│   │   │   ├── GlobalExceptionHandler.java    # 全局异常处理
│   │   │   └── ApiKeyInterceptor.java         # API Key 拦截校验
│   │   ├── config/                            # 配置层
│   │   │   ├── ElasticsearchConfig.java
│   │   │   ├── RocketMQConfig.java
│   │   │   └── WebMvcConfig.java              # 拦截器注册
│   │   ├── model/
│   │   │   ├── entity/                        # 数据库实体（9张表）
│   │   │   ├── dto/                           # 请求参数对象
│   │   │   └── vo/                            # 返回视图对象
│   │   ├── mapper/                            # MyBatis-Plus Mapper
│   │   ├── controller/                        # REST 控制器
│   │   ├── service/                           # 业务接口
│   │   ├── service/impl/                      # 业务实现
│   │   ├── pipeline/                          # 文档处理管道
│   │   │   ├── parser/                        # 文件解析（Tika）
│   │   │   ├── chunker/                       # 文本分块
│   │   │   ├── embedder/                      # 向量化（DashScope API）
│   │   │   └── indexer/                       # ES 索引写入
│   │   ├── search/                            # 检索引擎
│   │   │   ├── QueryRewriter.java             # Query 改写（DeepSeek）
│   │   │   ├── VectorSearchService.java       # pgvector 向量检索
│   │   │   ├── KeywordSearchService.java      # ES BM25 关键词检索
│   │   │   ├── HybridSearchService.java       # 混合检索 + RRF 融合
│   │   │   └── RerankerClient.java            # Reranker 微服务 HTTP 调用
│   │   └── mq/                                # 消息队列
│   │       ├── DocumentProcessProducer.java   # 生产者：发送处理任务
│   │       └── DocumentProcessConsumer.java   # 消费者：执行处理管道
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── application-dev.yml
│   │   └── db/migration/                      # SQL 迁移脚本
│   ├── Dockerfile
│   └── pom.xml
├── reranker/                                   # ← 新增：Reranker Python 微服务
│   ├── main.py                                # FastAPI 服务
│   ├── requirements.txt                       # transformers, torch, fastapi, uvicorn
│   └── Dockerfile
├── frontend/                                   # 已有 Vue 3 项目搬入
│   ├── src/
│   │   ├── views/                              # 6 个页面（已有）
│   │   ├── components/                         # Sidebar 等（已有）
│   │   ├── router/                             # 路由（已有）
│   │   └── api/                                # ← 新增：API 请求封装层
│   │       ├── request.js                      # axios 实例 + 拦截器
│   │       ├── kb.js                           # 知识库 API
│   │       ├── document.js                     # 文档 API
│   │       ├── search.js                       # 检索 API
│   │       ├── eval.js                         # 评测 API
│   │       └── admin.js                        # 管理 API
│   ├── package.json
│   └── vite.config.js
├── docker-compose.yml                          # PG + ES + RocketMQ + Backend + Reranker + Nginx
├── nginx.conf                                  # 前端静态文件 + API 代理
└── docs/
    └── architecture.md                         # 本文档
```

---

## 四、数据库设计

### 4.1 表结构（9 张表，PostgreSQL + pgvector）

```sql
-- 1. 知识库
CREATE TABLE knowledge_bases (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    embedding_model VARCHAR(50) DEFAULT 'text-embedding-v4',
    chunk_size INT DEFAULT 512,
    chunk_overlap INT DEFAULT 64,
    doc_count INT DEFAULT 0,
    chunk_count INT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'active',      -- active / indexing / disabled
    created_at TIMESTAMP DEFAULT now(),
    updated_at TIMESTAMP DEFAULT now()
);

-- 2. 文档
CREATE TABLE documents (
    id BIGSERIAL PRIMARY KEY,
    kb_id BIGINT NOT NULL REFERENCES knowledge_bases(id),
    filename VARCHAR(255) NOT NULL,
    file_path VARCHAR(500) NOT NULL,          -- 本地磁盘路径 /data/files/{uuid}.pdf
    file_size BIGINT,
    file_type VARCHAR(20),
    parse_status VARCHAR(20) DEFAULT 'pending', -- pending / parsing / chunking / embedding / indexing / completed / failed
    chunk_count INT DEFAULT 0,
    error_msg TEXT,
    created_at TIMESTAMP DEFAULT now()
);

-- 3. 文档块（pgvector 向量列，1024维 = text-embedding-v4）
CREATE TABLE document_chunks (
    id BIGSERIAL PRIMARY KEY,
    doc_id BIGINT NOT NULL REFERENCES documents(id),
    kb_id BIGINT NOT NULL REFERENCES knowledge_bases(id),
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    content_vector vector(1024),              -- text-embedding-v4 = 1024 维
    token_count INT,
    created_at TIMESTAMP DEFAULT now()
);
CREATE INDEX ON document_chunks USING ivfflat (content_vector vector_cosine_ops) WITH (lists = 100);

-- 4. 检索日志
CREATE TABLE retrieval_logs (
    id BIGSERIAL PRIMARY KEY,
    query TEXT NOT NULL,
    rewritten_queries TEXT,                   -- JSON数组：改写后的 queries
    strategy VARCHAR(30),                     -- vector / keyword / hybrid
    kb_ids VARCHAR(200),
    top_k INT,
    result_count INT,
    latency_ms INT,
    created_at TIMESTAMP DEFAULT now()
);

-- 5. 评测数据集
CREATE TABLE eval_datasets (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    kb_id BIGINT NOT NULL REFERENCES knowledge_bases(id),
    question_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT now()
);

-- 6. 评测问题
CREATE TABLE eval_questions (
    id BIGSERIAL PRIMARY KEY,
    dataset_id BIGINT NOT NULL REFERENCES eval_datasets(id),
    question TEXT NOT NULL,
    expected_doc_ids TEXT                     -- JSON数组：期望召回的文档ID，如 "[1,3,5]"
);

-- 7. 评测实验
CREATE TABLE eval_experiments (
    id BIGSERIAL PRIMARY KEY,
    dataset_id BIGINT NOT NULL REFERENCES eval_datasets(id),
    strategy VARCHAR(30) NOT NULL,            -- vector / keyword / hybrid
    enable_query_rewrite BOOLEAN DEFAULT true,
    enable_reranker BOOLEAN DEFAULT true,
    top3_hit_rate DECIMAL(5,2),
    top5_hit_rate DECIMAL(5,2),
    avg_latency_ms INT,
    status VARCHAR(20) DEFAULT 'running',
    created_at TIMESTAMP DEFAULT now()
);

-- 8. 评测结果
CREATE TABLE eval_results (
    id BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES eval_experiments(id),
    question_id BIGINT NOT NULL REFERENCES eval_questions(id),
    hit BOOLEAN DEFAULT false,
    hit_at INT,                               -- 在第几位命中（1/2/3...）
    recalled_chunk_ids TEXT,
    score DECIMAL(5,4),
    latency_ms INT
);

-- 9. API Key
CREATE TABLE api_keys (
    id BIGSERIAL PRIMARY KEY,
    key_name VARCHAR(100),
    api_key VARCHAR(64) UNIQUE NOT NULL,
    enabled BOOLEAN DEFAULT true,
    rate_limit INT DEFAULT 100,
    created_at TIMESTAMP DEFAULT now()
);
```

### 4.2 ER 关系

```
knowledge_bases  1──N  documents
knowledge_bases  1──N  document_chunks
documents        1──N  document_chunks
eval_datasets    1──N  eval_questions
eval_datasets    1──N  eval_experiments
eval_experiments 1──N  eval_results
eval_questions   1──N  eval_results
retrieval_logs   独立日志表
api_keys         独立配置表
```

---

## 五、ES 索引设计

```json
// PUT /ragforge_chunks
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0,
    "analysis": {
      "analyzer": {
        "ik_max_word_analyzer": {
          "type": "custom",
          "tokenizer": "ik_max_word"
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "chunk_id":    { "type": "long" },
      "doc_id":      { "type": "long" },
      "kb_id":       { "type": "long" },
      "filename":    { "type": "keyword" },
      "content":     { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "chunk_index": { "type": "integer" },
      "created_at":  { "type": "date" }
    }
  }
}
```

---

## 六、API 接口设计

### 6.1 知识库

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/kb` | 创建知识库 |
| GET | `/api/v1/kb` | 知识库列表（带 doc_count/chunk_count） |
| GET | `/api/v1/kb/{id}` | 知识库详情 |
| PUT | `/api/v1/kb/{id}` | 更新知识库 |
| DELETE | `/api/v1/kb/{id}` | 删除知识库（级联删除文档+chunk+ES索引） |

### 6.2 文档管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/kb/{kbId}/documents` | 上传文档（multipart/form-data），返回 document_id，后台异步处理 |
| GET | `/api/v1/kb/{kbId}/documents` | 文档列表（分页） |
| GET | `/api/v1/documents/{id}` | 文档详情 + 元信息 + chunk 列表 |
| GET | `/api/v1/documents/{id}/status` | 查看异步处理状态 |
| DELETE | `/api/v1/documents/{id}` | 删除文档 + 级联删除 chunk + ES 文档 |

### 6.3 检索（核心）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/search` | 完整检索链路 |

```json
// Request
{
  "query": "2026年后端开发需要掌握哪些AI技能",
  "kb_ids": [1, 3],
  "strategy": "hybrid",          // vector | keyword | hybrid
  "top_k": 8,
  "enable_query_rewrite": true,   // 是否启用 Query 改写
  "enable_reranker": true         // 是否启用 Reranker 精排
}

// Response
{
  "code": 200,
  "data": {
    "results": [
      {
        "chunk_id": 123,
        "doc_id": 12,
        "filename": "字节JD.pdf",
        "content": "...有大模型应用开发经验者优先...",
        "score": 0.94,
        "score_detail": {
          "vector_score": 0.92,
          "keyword_score": 0.88,
          "rrf_score": 0.91,
          "reranker_score": 0.94
        }
      }
    ],
    "rewritten_queries": ["2026年 后端开发 需要掌握 哪些 AI 技能", "后端工程师 AI 能力要求 2026"],
    "latency_ms": 680,
    "strategy": "hybrid",
    "reranker_applied": true
  }
}
```

### 6.4 评测

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/eval/datasets` | 创建评测数据集 |
| GET | `/api/v1/eval/datasets` | 数据集列表 |
| GET | `/api/v1/eval/datasets/{id}` | 数据集详情（含问题列表） |
| POST | `/api/v1/eval/datasets/{id}/questions` | 批量添加问题 |
| POST | `/api/v1/eval/experiments/run` | 执行评测实验 |
| GET | `/api/v1/eval/experiments/{id}` | 实验详情 + 结果列表 + 失败分析 |

```json
// POST /api/v1/eval/experiments/run
{
  "dataset_id": 1,
  "strategy": "hybrid",
  "enable_query_rewrite": true,
  "enable_reranker": true,
  "top_k": 5
}
```

### 6.5 系统

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/dashboard` | 驾驶舱指标 |
| GET | `/api/v1/admin/metrics` | 详细系统指标 |
| GET | `/api/v1/health` | 健康检查 |

### 6.6 统一规范

- 鉴权：所有接口 Header 带 `X-API-Key: sk-ragforge-xxxx`
- 分页：`?page=1&size=20`，响应含 `{ total, page, size, list }`
- 错误响应：`{ code: 400, msg: "知识库不存在", data: null }`

---

## 七、文档处理异步管道

### 7.1 流程图

```
POST /api/v1/kb/{kbId}/documents
  │
  ├─→ 1. 接收文件，存本地磁盘 → /data/files/{uuid}.pdf
  ├─→ 2. INSERT documents (file_path, parse_status='pending')
  ├─→ 3. 发送 RocketMQ Message: { documentId: 123 }
  └─→ 4. 返回 { documentId: 123, status: "processing" }

                     ↓↓↓ 异步 ↓↓↓

RocketMQ Consumer 接收消息
  │
  ├─→ 5. 更新 parse_status = 'parsing'
  ├─→ 6. Apache Tika 解析 → 纯文本
  ├─→ 7. 更新 parse_status = 'chunking'
  ├─→ 8. 文本分块 → List<Chunk> (512 tokens, overlap 64)
  ├─→ 9. 更新 parse_status = 'embedding'
  ├─→ 10. 逐块调用 DashScope text-embedding-v4 API → vector(1024)
  ├─→ 11. 更新 parse_status = 'indexing'
  ├─→ 12. 批量 INSERT document_chunks (含 content_vector)
  ├─→ 13. 批量写入 ES 索引 (content + metadata)
  ├─→ 14. 更新 documents.parse_status = 'completed'
  ├─→ 15. 更新 knowledge_bases.doc_count, chunk_count
  │
  └─→ 失败处理：
       └─→ 更新 parse_status = 'failed', error_msg = 异常信息
       └─→ RocketMQ 自动重试（最多 3 次，之后进入死信队列）
```

### 7.2 RocketMQ Topic 设计

```
Topic: ragforge-document-process
  Producer: DocumentProcessProducer (controller 调用)
  Consumer: DocumentProcessConsumer (pipeline 编排)
  消费组: ragforge-doc-process-group
  重试次数: 3
```

### 7.3 同步 vs 异步

| 阶段 | 耗时 | 同步 | 异步 |
|------|------|------|------|
| 文件解析 | 1-5s | 用户等 | 后台跑 |
| 分块 | <1s | 用户等 | 后台跑 |
| Embedding (50 chunks) | 3-10s | 用户等 | 后台跑 |
| ES 索引 | <1s | 用户等 | 后台跑 |
| **用户感知耗时** | — | **10-20s** | **<200ms** |

---

## 八、检索策略设计（完整链路）

### 8.1 检索流程图

```
POST /api/v1/search { query, kb_ids, ... }

  Step 1: Query 改写（DeepSeek-V3）
  ┌──────────────────────────────────────────┐
  │ 原始 query: "后端开发需要什么技能"         │
  │     ↓ DeepSeek 扩充                       │
  │ 改写结果: [                               │
  │   "后端开发工程师 技能要求",              │
  │   "后端开发 技术栈 必备能力",             │
  │   "Java后端 需要掌握 技术"                │
  │ ]                                         │
  └──────────────────────────────────────────┘
                    │
                    ▼
  Step 2: 双路并发召回
  ┌─────────────────┐  ┌─────────────────┐
  │ pgvector 向量    │  │ ES BM25 关键词   │
  │ cosine_distance  │  │ ik_max_word     │
  │ TopK × 2 = 20   │  │ TopK × 2 = 20   │
  └────────┬────────┘  └────────┬────────┘
           │                    │
           └──────┬─────────────┘
                  ▼
  Step 3: RRF 融合排序
  ┌──────────────────────────────────────────┐
  │ RRF_score(chunk) = Σ 1/(60 + rank_i)     │
  │ 合并去重 → 候选集 TopK×2 = 16 条         │
  └──────────────────────────────────────────┘
                  │
                  ▼
  Step 4: Reranker 精排
  ┌──────────────────────────────────────────┐
  │ HTTP POST → reranker:8081/rerank          │
  │ Body: { query, passages: [16条] }        │
  │ Response: [{ index: 3, score: 0.94 }, ..]│
  │ 取 TopK = 8 返回                          │
  └──────────────────────────────────────────┘
```

### 8.2 四种检索模式对比

```
strategy=vector   → pgvector 向量检索（无改写、无融合、无精排）
strategy=keyword  → ES BM25 关键词检索
strategy=hybrid   → 向量 + BM25 + RRF 融合
strategy=full     → 改写 + 向量 + BM25 + RRF + Reranker（完整链路）
```

### 8.3 RRF 算法

```
RRF_score(chunk) = Σ (1 / (k + rank_i))
  k = 60（常数）
  rank_i = chunk 在第 i 个检索结果中的排名

如果 chunk 只在一个结果集中出现，另一个取 rank=∞（贡献 0）
```

### 8.4 pgvector 检索 SQL

```sql
SELECT dc.id, dc.content, dc.doc_id, d.filename,
       1 - (dc.content_vector <=> ?) AS similarity
FROM document_chunks dc
JOIN documents d ON dc.doc_id = d.id
WHERE dc.kb_id = ANY(?)
ORDER BY dc.content_vector <=> ?
LIMIT ?;
```

### 8.5 ES BM25 检索

```json
POST /ragforge_chunks/_search
{
  "query": {
    "bool": {
      "must": [{ "match": { "content": "后端开发 AI 技能" } }],
      "filter": [{ "terms": { "kb_id": [1, 3] } }]
    }
  },
  "size": 20
}
```

---

## 九、前端页面与 API 映射

| 页面 | 路由 | 调用后端接口 | 说明 |
|------|------|-------------|------|
| 驾驶舱 | `/` | GET `/api/v1/admin/dashboard` | 4 个指标卡片 + 最近操作 |
| 知识库管理 | `/knowledge` | GET/POST `/api/v1/kb`, POST 上传 | 知识库列表 + 上传区 |
| 文档详情 | `/document/:id` | GET `/api/v1/documents/{id}` | Chunk 列表 + 元信息 |
| 检索调试台 | `/debug` | POST `/api/v1/search` | 搜索框（含改写开关）+ 结果 + 分数明细 |
| 评测实验室 | `/eval` | GET/POST `/api/v1/eval/*` | 评测集 + 实验对比 + 消融实验 |
| API 网关 | `/api` | GET `/api/v1/admin/metrics` | API 文档展示 + Key 管理 |

前端已有 6 个页面的 Vue 组件，样式和交互都写好了，当前是 mock 数据。任务 17 集中做 API 对接。

---

## 十、部署架构

```yaml
# docker-compose.yml
services:
  postgres:
    image: pgvector/pgvector:pg15
    ports: ["5432:5432"]
    environment:
      POSTGRES_DB: ragforge
      POSTGRES_USER: ragforge
      POSTGRES_PASSWORD: ragforge123
    volumes:
      - pgdata:/var/lib/postgresql/data

  elasticsearch:
    image: elasticsearch:8.11.0
    ports: ["9200:9200"]
    environment:
      discovery.type: single-node
      xpack.security.enabled: false
      ES_JAVA_OPTS: "-Xms512m -Xmx512m"
    volumes:
      - esdata:/usr/share/elasticsearch/data

  rocketmq-namesrv:
    image: apache/rocketmq:5.1.0
    command: sh mqnamesrv
    ports: ["9876:9876"]

  rocketmq-broker:
    image: apache/rocketmq:5.1.0
    command: sh mqbroker -n rocketmq-namesrv:9876
    ports: ["10911:10911"]
    depends_on: [rocketmq-namesrv]

  reranker:                                    # ← 新增：Reranker 微服务
    build: ./reranker
    ports: ["8081:8081"]
    environment:
      MODEL_NAME: BAAI/bge-reranker-v2-m3
      DEVICE: cpu

  backend:
    build: ./backend
    ports: ["8080:8080"]
    depends_on: [postgres, elasticsearch, rocketmq-broker, reranker]
    environment:
      SPRING_PROFILES_ACTIVE: docker
    volumes:
      - files:/data/files

  nginx:
    image: nginx:alpine
    ports: ["80:80"]
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf
      - ./frontend/dist:/usr/share/nginx/html
    depends_on: [backend]

volumes:
  pgdata:
  esdata:
  files:
```

```nginx
# nginx.conf
server {
    listen 80;

    location / {
        root /usr/share/nginx/html;
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

---

## 十一、实施任务清单（18 个）

| # | 阶段 | 任务 | 工作日 |
|---|------|------|--------|
| **P1 地基（3 天）** | | | |
| 1 | 基础 | Spring Boot 项目脚手架 + PG/pgvector + ES + RocketMQ 环境配通 | 1.5d |
| 2 | 基础 | 9 张表 DDL + MyBatis-Plus 代码生成 entity/mapper | 1d |
| 3 | 基础 | 统一响应体、全局异常处理、ApiKey 拦截器 | 0.5d |
| **P2 知识库（2 天）** | | | |
| 4 | 知识库 | 知识库 CRUD 完整实现（含级联删除校验） | 1d |
| 5 | 文档 | 文档上传（存本地磁盘）+ 列表/详情/状态查询/删除 | 1d |
| **P3 异步管道（4 天）** | | | |
| 6 | 管道 | Apache Tika 文档解析器（PDF/Word/Markdown → 纯文本） | 1d |
| 7 | 管道 | 文本分块器（滑动窗口）+ DashScope Embedding 向量化 | 1d |
| 8 | 管道 | ES 索引写入（创建索引、批量写入、删除同步） | 0.5d |
| 9 | 管道 | RocketMQ Producer + Consumer 编排全流程 | 1.5d |
| **P4 检索引擎（5 天）** | | | |
| 10 | 检索 | pgvector 向量检索（cosine distance、TopK、按知识库过滤） | 1d |
| 11 | 检索 | ES BM25 关键词检索（ik 分词、字段权重） | 1d |
| 12 | 检索 | **Query 改写**：DeepSeek 扩写/分解 query | 1d |
| 13 | 检索 | 混合检索 + RRF 融合排序 | 1d |
| 14 | 检索 | **Reranker 微服务**（Python FastAPI + bge-reranker-v2-m3） | 1d |
| **P5 评测（1.5 天）** | | | |
| 15 | 评测 | 评测数据集管理（创建、添加问题、标注期望文档） | 0.5d |
| 16 | 评测 | 实验执行引擎 + 消融实验（对比 4 种策略）+ 失败分析 | 1d |
| **P6 前端 + 部署（2.5 天）** | | | |
| 17 | 前端 | 前端 6 页面 API 对接（新增 api/ 请求层，替换 mock） | 1.5d |
| 18 | 部署 | Docker Compose 编排 + Nginx + 驾驶舱指标接口 | 1d |

**总计：约 18 个工作日，4 周（每天 3-4 小时）**

---

## 十二、扩展预留（面试主动讲）

| 能力 | v1 状态 | 预留设计 |
|------|---------|---------|
| 多模态（图片OCR） | 不做 | Parser 接口 + 策略模式，新增 ImageParser 实现类 |
| 文件存 OSS | 不做 | FileStorageService 接口，本地 → OSS 一键切换 |
| 多租户 | 不做 | api_keys 表预留，拦截器扩展 |
| K8s 部署 | 不做 | Dockerfile 已有，推镜像即可上 ACK |
| Query 改写 | ✅ 已做 | DeepSeek-V3 |
| Reranker 精排 | ✅ 已做 | bge-reranker-v2-m3 独立微服务 |
| 大文件异步 | ✅ 已做 | RocketMQ 管道，Consumer 可水平扩展 |
