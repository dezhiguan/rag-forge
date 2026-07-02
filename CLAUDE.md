# RAGForge — AI 开发上下文恢复指南

> 每次新会话开始,Claude Code 会自动加载本文件。
> 本文件是项目的"记忆锚点",保持精简,详细内容链接到 `docs/`。
> **以 `docs/architecture.md` 为权威架构文档,以本仓库当前代码为最终事实。**

---

## 项目身份

- **项目名称**:RAGForge — RAG 知识引擎
- **定位**:RAG 基础设施层,为 [CareerMate(AI 求职 Agent)](https://github.com/dezhiguan/careermate) 等上层应用提供知识检索 / RAG 应答 / MCP API
- **线上**:https://ragforge.net (已上线运行)
- **后端**:Java 21 + Spring Boot 3.5.15 + MyBatis-Plus
- **数据**:PostgreSQL + pgvector(`vl_vector` 2560 维)、Elasticsearch 8.15(BM25)、RocketMQ、Redis
- **AI**:Spring AI 1.0(MCP WebMVC SSE);模型走 DashScope + DeepSeek(见下)
- **前端**:Vue 3 + Vite + Element Plus(纯 JavaScript)
- **部署**:k3s 单节点(应用层),数据层独立机,入口层 Nginx

## 项目位置

`/Users/amy/CursorProject/rag-forge/`(GitHub: `dezhiguan/rag-forge`)

## 架构师与协作模式

- 架构师 @guandezhi(Java 技术栈,该项目 0→1)。架构师负责架构设计 / 需求 / 数据库 / API / 任务拆分 / Cursor prompt;**具体代码由 Cursor 执行**,本会话主要维护架构与文档、审核代码。
- 重大架构变更需同步更新 `docs/architecture.md` 与本文件。

## 核心架构决策(速查 · 以代码为准)

### 模型(按 Purpose)
| Purpose | 模型 | 供应商 | 说明 |
|------|------|------|------|
| EMBEDDING | `qwen3-vl-embedding` | DashScope | 文本+图片统一 **2560 维** |
| REWRITE | `qwen-turbo` | DashScope | Query 改写(支持动态选型) |
| ANSWER | `qwen-plus` | DashScope | RAG 应答 / 调试台(支持动态选型) |
| RERANK | `qwen3-rerank` | DashScope | 仅 `full` 策略 |
| OCR | `qwen-vl-ocr` | DashScope | 图片管道可选 |
| JUDGE | `deepseek-v4-flash` | DeepSeek | LLM-as-Judge 评测 |

> ⚠️ 历史误区(已纠正):早期文档写的 text-embedding-v4 / DeepSeek-V3 改写 / 本地 bge-reranker 微服务**均已不是现状**。rerank 走 DashScope 在线,`reranker/`(jina)线上未部署。

### 中间件
- PostgreSQL + pgvector:业务数据 + 向量(`document_chunks.vl_vector vector(2560)`)
- Elasticsearch 8.15 + IK:BM25 关键词检索(IK 缺失回退 standard)
- RocketMQ:文档处理异步管道(topic `ragforge-document-process`,group `ragforge-doc-process-group`)
- Redis:认证撤销、API Key 限流、ShedLock
- 文件存储:节点本地 `hostPath /data/files`(OSS 抽象已就绪,默认本地盘)

### 检索链路(5 策略,统一 `RetrievalService`)
```
vector(默认) / keyword / hybrid(RRF) / rewrite(改写+多路向量) / full(改写+混合+rerank)
```
每策略独立并发限流 + 超时;`full` 默认并发=1,是唯一调用 rerank 的策略。

### 向量索引现状(重要)
`vl_vector` 为 2560 维 > pgvector 0.8 索引上限 2000,**当前无 HNSW,向量检索走顺序扫描**。切换见 `backend/src/main/resources/db/manual/V27__vl_unified_vector.sql`。

### 数据库
Flyway `V1..V51`(baseline=26,out-of-order),核心表 ~26 张:knowledge_bases / documents / document_chunks / retrieval_logs / kb_acl / answer_logs / clean_profiles / eval_* / judge_* / model_config / model_usage_daily / organizations / org_members / api_keys / revoked_jtis / admin_access_audit 等。

### 认证与权限
Auth Gateway 颁发 JWT(RS256),后端自研 `JwtVerifier`(JWKS 验签,非 nimbus)。角色为字符串约定 `ADMIN / KB_EDITOR / KB_VIEWER / SERVICE_ACCOUNT`。KB 访问统一过 `KbAccessGuard`。组织模型为 GitHub 式个人+组织(已移除 tenant)。
会话:access 15min/refresh 旋转(7d,记住我 30d 滑动),网关 60s 旋转宽限期;前端 `api/session.js` 主动续期+跨标签页 Web Locks 单飞+失败分级(仅 401/403 踢登录)。详见 `docs/dev/security-and-multitenancy.md` §8。

### 部署(k3s)
`ragforge` 命名空间,同一 backend 镜像按 `RAGFORGE_ROLE` 起 `api`(3)/ `worker`(1,MQ consumer)/ `judge`(1,LLM-as-Judge)+ `frontend`(2)。入口:域名 → 入口层 Nginx → 应用层 NodePort 31090。详见 `docs/deploy/deployment-architecture.md`。

## 文档结构(已按 原型/开发/测试/部署 归类)

```
docs/
├── architecture.md          # 权威架构(优先读)
├── CHANGELOG.md             # 版本演进时间线(V4→V5→V6 + 认证/权限 V1→V2)
├── INDEX.md                 # 文档索引导航
├── prototype/               # 早期设计稿与原型(历史)
├── dev/                     # 实现说明、任务、路线、设计规格
├── test/                    # 测试计划、用例、验收、排障
└── deploy/                  # 部署、运维、监控(以 deployment-architecture.md 为准)
```

## 恢复上下文的步骤

1. 读本文件(自动加载)
2. 读 `docs/architecture.md` —— 权威架构
3. 读 `docs/dev/tasks.md` 与 `docs/CHANGELOG.md` —— 进度与演进
4. `git log` / `git diff` —— 看最近实际改了什么代码

## 协作规则

1. 改动以**当前代码为最终事实**,文档若与代码冲突,先核代码再改文档。
2. 重大架构变更必须同步 `docs/architecture.md` 与本文件。
3. 提交代码时 commit message 标注对应任务编号;**不要提交测试文件**(测试仅本地验证),合并前后端均需编译通过(后端 `mvn test-compile`,前端 `npm run build`)。
