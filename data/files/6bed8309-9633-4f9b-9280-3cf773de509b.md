# 07 - 变更记录

按版本记录核心变更。完成时间可在发版或验收时补全。

---

## V11：Token 用量与成本监控

### V11-02：qwen/text-embedding-v4 Embedding 成本统计 ✅

| 项 | 内容 |
|----|------|
| **数据库** | `rag_query_log` 新增 `embedding_tokens`、`embedding_cost`、`total_cost` |
| **成本口径** | Embedding Token = 问题文本估算；费用 = tokens/1000×¥0.0007；`totalCost = embeddingCost + chatCost` |
| **API** | `tokenUsage` 增加 embedding/chat/total 分项字段；Debug 与参数实验台一致 |
| **前端** | Token 卡片 Embedding / Chat / Total 三块；Token 成本页 qwen Embedding 统计 |
| **兼容** | 老日志无 embedding 字段时按 0 处理，不报错 |

### V11-01：Token 用量与成本监控最小闭环 ✅

| 项 | 内容 |
|----|------|
| **后端** | `TokenUsageService` 工程估算（中文 1 字≈1 token，其他约 4 字符≈1 token）；`TokenUsageResponse` |
| **Debug / 实验台** | 查询响应增加 `tokenUsage`；`rag_query_log` 持久化 Token 与 `estimated_cost` |
| **定价** | 静态单价：deepseek-chat / deepseek-v4-pro / mock / local；未知模型显示「未配置价格」 |
| **前端** | Debug / Debug 详情 / 参数实验台 KPI 卡片；对比表增加总 Token、预估费用列 |
| **看板** | `/token-cost`「Token 成本」页；`GET /api/token-cost/overview` 累计与近 7 天统计 |
| **菜单** | 可观测分组新增 Token 成本；guest / admin 均可查看 |

---

## V10.5：线上体验与页面布局优化

### V10.5-01：全局 UI/UX 优化与项目总览页重构 ✅

| 项 | 内容 |
|----|------|
| **变更** | 顶部横向菜单改为左侧分组 Sidebar + 精简顶栏；新增 `theme.css` 统一视觉变量与通用 class |
| **项目总览** | `/project-overview` Tab 化：项目概览、能力路线、部署状态、快速开始、适用场景；首屏信息压缩 |
| **宽表格** | Debug / Evaluation / 参数实验台 / 查询日志 / 慢查询等宽表增加 `rag-table-scroll` 横向滚动容器 |
| **权限** | admin 完整菜单、guest 按 `guestVisible` 过滤；登录 / 退出 / 只读模式逻辑不变 |
| **范围** | 仅前端布局与视觉，不修改后端业务逻辑与接口 |

---

## V10：访问保护与只读体验模式

### V10-01：新增登录入口与体验账号访问保护 ✅

| 项 | 内容 |
|----|------|
| **变更** | `/login` 登录页；`POST /api/auth/login`、`GET /api/auth/me`、`POST /api/auth/logout`；Token 鉴权拦截器；内置 admin / guest 账号；项目总览展示访问身份 |
| **前端** | Pinia auth store、路由守卫、Axios Bearer token、布局栏用户与退出 |
| **配置** | `rag.auth.*`；`AUTH_ADMIN_PASSWORD` / `AUTH_GUEST_PASSWORD` 环境变量 |
| **放行** | `GET /api/system/health` 无需登录 |
| **未改** | guest 危险操作限制留待 V10-02 |

### V10-02：体验账号只读模式与危险操作保护 ✅

| 项 | 内容 |
|----|------|
| **变更** | guest 只读体验模式；`GuestWriteInterceptor` 后端拦截危险写 API；前端按钮禁用与 `requireWrite` 提示 |
| **guest 禁止** | 创建/删除知识库、上传文档、初始化样例、重建向量、重建 ES 索引 |
| **guest 允许** | Debug/Evaluation/实验台查询、系统状态、RAG 指标、查询日志、慢查询分析 |
| **模式展示** | 管理员模式 / 只读体验模式（项目总览 + 顶栏） |
| **admin** | 完整权限不变 |

---

## V0：项目骨架版

| 项 | 内容 |
|----|------|
| **完成时间** | （待填） |
| **核心变更** | Spring Boot + Vue 3 骨架；统一响应与异常；健康检查；基础布局与 Dashboard |
| **新增接口** | `GET /api/system/health`、`GET /api/dashboard/stats` |
| **新增页面** | `/dashboard` |
| **新增表/索引** | 无业务表 |
| **备注** | PostgreSQL 数据源配置 |

---

## V1：文档导入与分块版

| 项 | 内容 |
|----|------|
| **完成时间** | （待填） |
| **核心变更** | 知识库 CRUD；Markdown/TXT 上传解析；固定大小分块；样例数据初始化 |
| **新增接口** | `/api/kb/*`、`/api/kb/{kbId}/documents/*`、`/api/documents/*`、`/api/sample/*` |
| **新增页面** | `/kb`、`/kb/:kbId/documents`、`/documents/:documentId/chunks` |
| **新增表** | `knowledge_base`、`document`、`document_chunk` |
| **备注** | 不含 Embedding、Chat |

---

## V2：Naive RAG 问答版

| 项 | 内容 |
|----|------|
| **完成时间** | （待填） |
| **核心变更** | PgVector 向量化；向量 TopK；Chat 问答；Prompt 与引用来源；会话消息 |
| **新增接口** | `/api/kb/{kbId}/embedding/*`、`POST /api/chat`、`GET /api/chat/sessions/*` |
| **新增页面** | `/chat` |
| **新增表** | `chunk_embedding`、`chat_session`、`chat_message` |
| **备注** | Mock Embedding / Mock Chat |

---

## V2.5：真实模型接入版

| 项 | 内容 |
|----|------|
| **完成时间** | （待填） |
| **核心变更** | Qwen Embedding、DeepSeek Chat；Provider 路由与配置切换；向量一致性校验 |
| **新增接口** | `GET /api/model/providers` |
| **新增页面** | （无独立页，配置驱动） |
| **新增表** | 无 |
| **备注** | 切换 Embedding 后需 `embedding/rebuild` |

---

## V3：RAG Debug 可观察版

| 项 | 内容 |
|----|------|
| **完成时间** | （待填） |
| **核心变更** | Debug 全链路展示；Context 过滤；查询历史；耗时统计；`usedInPrompt` / `filterReason` |
| **新增接口** | `POST /api/debug/query`、`GET /api/debug/query-logs`、`GET /api/debug/query-logs/{id}` |
| **新增页面** | `/debug`、`/debug/:queryLogId` |
| **新增表** | `rag_query_log`、`rag_retrieval_log`（含过滤字段） |
| **备注** | Context 过滤 10 用例自动化通过（见 `06-debug-log.md`） |

---

## V4：关键词检索版

| 项 | 内容 |
|----|------|
| **状态** | ✅ 已完成 |
| **完成时间** | （待填） |
| **核心变更** | Elasticsearch 集成；索引 `rag_document_chunk`；`terms` 专有词字段；BM25 加权查询；Debug `searchMode` 支持 VECTOR / BM25；BM25「SMS_429 是什么意思？」Top1 排序问题已修复 |
| **新增接口** | `POST /api/search/index/rebuild`、`POST /api/search/bm25`；Debug 请求/响应增 `searchMode` |
| **新增页面** | Debug 页增强（模式切换、重建索引、快捷问题） |
| **新增表/索引** | **无** 新 PG 表；**ES 索引** `rag_document_chunk`；`rag_query_log.search_mode` |
| **备注** | `/api/chat` 仍为纯向量检索；V4 验收通过后项目进入 V5 |

---

## V5：Hybrid Search 混合检索版

| 项 | 内容 |
|----|------|
| **状态** | 🔄 当前 / 启动中 |
| **完成时间** | （未开始） |
| **规划核心变更** | Vector + BM25 应用层融合（如 RRF）；`HybridSearchService` 编排；Debug `searchMode=HYBRID`；融合结果不落库 |
| **规划接口** | Debug `HYBRID`（及可选独立 Hybrid API，待 V5-03/04 定稿） |
| **规划页面** | Debug 页 HYBRID 模式（V5-05） |
| **新增表/索引** | **无**（复用 V4 ES 与既有 PG 表） |
| **备注** | V5-01 文档切换；V5-02 见下 |

### V5-02：融合排序核心逻辑（2026-05）

| 项 | 内容 |
|----|------|
| **变更** | 新增 `module/search/hybrid/`：`HybridSearchMerger`（RRF）、`HybridSearchMergeItem`、`HybridSearchResult` |
| **逻辑** | 按 chunkId 合并 Vector/BM25；BM25 分数按 Top1 归一化到 [0,1] 写入 `bm25Score`；RRF 计算 `hybridScore` 并 topK 截断 |
| **测试** | `HybridSearchMergerTest`：合并、归一化、排序、截断、空输入；`mvn test -Dtest=HybridSearchMergerTest` |
| **未做** | 前端、Controller、HYBRID searchMode、DB、ES mapping |
| **原因** | 验证 V5 Hybrid 融合排序核心逻辑，为 V5-03 双路召回编排做准备 |

### V5-03：Debug HYBRID 闭环

| 项 | 内容 |
|----|------|
| **变更** | `HybridSearchService`；`SearchMode.HYBRID`；`DebugService` HYBRID 分支；`DebugView` / `DebugDetailView` 增加 Hybrid 选项 |
| **流程** | Vector + BM25 → RRF 融合 → Context 过滤 → Prompt → Answer |
| **页面验证** | `/debug` 选择 HYBRID，问题如「SMS_429 是什么意思？」，展示 retrievedChunks / context / prompt / answer |

### V5-04：Debug 查询历史展示优化

| 项 | 内容 |
|----|------|
| **变更** | `frontend/src/views/DebugView.vue`：历史改为 Drawer；按钮「查询历史」；前端分页 |
| **原因** | 历史默认展开占用主页面空间；记录多时页面过长 |
| **未改** | 后端接口、Vector/BM25/Hybrid 查询逻辑 |

### V5-05：Hybrid 检索结果可观察性

| 项 | 内容 |
|----|------|
| **变更** | `DebugRetrievedChunkResponse` 增 HYBRID 观测字段；`DebugService.toRetrievedChunksFromHybrid`；`DebugView` / `DebugDetailView` 表格列；`utils/hybridDebug.ts` |
| **原因** | 页面难以观察 Vector/BM25 融合来源与各路人马分数 |
| **未改** | 数据库、ES mapping；`/api/chat`；VECTOR/BM25 展示逻辑 |

### V6-01：Debug 轻量 Reranker 重排闭环

| 项 | 内容 |
|----|------|
| **变更** | `RerankService`、`RerankResult`；Debug `enableRerank`；`DebugView` 重排开关与排名列 |
| **原因** | 进入 V6，需在页面观察重排前后效果，暂不接外部模型 |
| **未改** | ES mapping；`/api/chat` |

### V6-02：Reranker 可观察性增强与 V6 收尾

| 项 | 内容 |
|----|------|
| **变更** | `utils/rerankDebug.ts`；`DebugView` / `DebugDetailView` 排名变化 Tag、重排摘要、Context 顺序提示；`rag_query_log.enable_rerank`、`rag_retrieval_log` 增补 `original_rank` / `rerank_rank` / `rerank_score`；`DebugService` 持久化与详情回放 |
| **原因** | 完成 V6：页面直观看重排升降、详情页与历史一致；标记 V6 完成、V7 为下一阶段 |
| **未改** | 新增业务表、ES mapping；Evaluation；外部 Reranker |

### V6 版本完成摘要

- Debug「启用重排」+ 轻量 `RerankService`
- VECTOR / BM25 / HYBRID + Reranker
- 展示 `originalRank` / `rerankRank` / `rerankScore` / 排名变化
- Context / Prompt / Answer 使用重排后顺序

### V7-01：评测中心最小闭环

| 项 | 内容 |
|----|------|
| **变更** | `module/evaluation`（`EvaluationController`、`EvaluationService`、`EvaluationTestCatalog`）；`GET /api/evaluation/cases`、`POST /api/evaluation/run`；`EvaluationView.vue`、`/evaluation` 路由与菜单 |
| **原因** | 进入 V7，页面可批量验收 VECTOR / BM25 / HYBRID Top1 命中率 |
| **未改** | 评测表、ES mapping |

### V7-02：多模式对比与 Reranker 评测

| 项 | 内容 |
|----|------|
| **变更** | `POST /api/evaluation/compare`；`enableRerank` 于 run/compare；`EvaluationView` 单模式/多模式对比、对比统计与分模式明细；`RerankService` 接入评测 Top1 |
| **原因** | 完成 V7：页面直观对比 Hybrid / Reranker 是否提升通过率 |
| **未改** | 评测表、Recall@K / MRR |

### V7 版本完成摘要

- 评测中心单模式 + 三模式对比
- 可选轻量 Reranker 参与 Top1 判定
- 通过率、失败数、平均耗时可观察

---

## V8：工程化增强

### V8-01：系统运行状态看板

| 项 | 内容 |
|----|------|
| **变更** | `SystemStatusService`、`GET /api/system/status`；`rag.app.version`；`SystemStatusView.vue`、`/system-status` 路由与菜单；Dashboard 版本与入口更新 |
| **原因** | V8 工程化：页面可观察后端、PostgreSQL、ES、Provider 与核心能力状态 |
| **未改** | 权限、多租户、评测历史落库 |

### V8-02：RAG 运行指标看板

| 项 | 内容 |
|----|------|
| **变更** | `module/metrics`（`RagMetricsController`、`RagMetricsService`、`RagMetricsMapper`）；`GET /api/rag/metrics`；`RagMetricsView.vue`、`/rag-metrics` 路由与菜单 |
| **原因** | V8 工程化：复用 `rag_query_log` 展示查询次数、耗时、检索模式、Reranker 与慢查询 Top 10 |
| **未改** | 新增表、Chat 写日志、Debug / Evaluation 逻辑 |

### V8-03：RAG 查询日志中心

| 项 | 内容 |
|----|------|
| **变更** | `module/querylog`（`RagQueryLogController`、`RagQueryLogService`）；`GET /api/rag/query-logs`；`RagQueryLogsView.vue`、`/rag-query-logs` 路由与菜单 |
| **原因** | V8 工程化：分页 + 筛选浏览 `rag_query_log`，详情复用 Debug 详情页 |
| **未改** | `rag_query_log` 表结构、Debug 写入与列表接口 |

### V8-04：慢查询分析与优化建议

| 项 | 内容 |
|----|------|
| **变更** | `SlowQueryAnalyzer`、`RagSlowQueryService`；`GET /api/rag/query-logs/slow-analysis`；`SlowQueryAnalysisView.vue`、`/slow-query-analysis` 路由与菜单 |
| **原因** | V8 工程化：基于日志规则生成慢查询原因与优化建议，辅助排查 |
| **未改** | 新增表、Debug / 查询日志中心分页接口 |

### V8-05：RAG 参数实验台

| 项 | 内容 |
|----|------|
| **变更** | `RagQueryPipelineService`、`ContextFilterOptions`；`POST /api/experiment/rag-query`；`RagExperimentView.vue`、`/rag-experiment`；DebugService 委托 Pipeline |
| **原因** | V8 工程化：页面调整 RAG 参数并观察召回 / Context / Prompt / Answer / 耗时 |
| **未改** | 全局 `rag.context` 配置、其他 V8 页面 |

### V8-06：参数实验台多组对比

| 项 | 内容 |
|----|------|
| **变更** | `RagExperimentView.vue` 实验对比区；加入对比 / 清空 / 展开详情；最多保留 5 组；`ExperimentCompareEntry` 类型 |
| **原因** | V8 工程化：同页对比不同参数组合对召回、Context、耗时与 Answer 的影响 |
| **未改** | 后端接口、对比结果落库 |

### V8-07：项目总览与能力导航

| 项 | 内容 |
|----|------|
| **变更** | `ProjectOverviewView.vue`、`/project-overview` 路由与「项目总览」菜单；版本路线图、功能入口、推荐使用流程、核心能力 |
| **原因** | V8 工程化：集中展示平台能力与导航，便于学习复盘与开源参考 |
| **未改** | 后端接口、既有页面逻辑 |

### V8-08：项目收尾与开源说明

| 项 | 内容 |
|----|------|
| **变更** | 完善 `README.md`；`ProjectOverviewView` 增加快速开始、项目完成状态（V0～V8）、适用场景 |
| **原因** | V8 收尾：便于新用户上手、学习与开源参考 |
| **未改** | 后端业务接口与既有页面逻辑 |

### V9-01：生产环境部署准备

| 项 | 内容 |
|----|------|
| **变更** | `application-prod.yml`、`.env.prod.example`、`frontend/.env.production.example`、`deploy/nginx.conf.example`、`scripts/run-backend-prod.sh`、`docs/08-production-deployment.md`；README 生产部署章节；项目总览「线上部署准备」 |
| **原因** | V9 阿里云部署前准备：清晰的生产配置、构建方式与 Nginx 反代说明 |
| **未改** | 业务 API、Debug / Evaluation / 实验台 / 日志 / 指标等页面逻辑 |

### V9-02：阿里云 ECS 部署环境准备

| 项 | 内容 |
|----|------|
| **变更** | `docs/10-aliyun-ecs-setup.md`、`scripts/check-ecs-env.sh`；`docs/09-production-deployment.md` 补充 ECS 前置步骤；项目总览增加 ECS 准备项；AI 求职 Agent 同机隔离说明 |
| **原因** | V9 实机部署前：明确 ECS 规格、初始化与环境检查 |
| **未改** | 业务 API、未实际创建云资源 |

### V9-03：双服务器部署配置落地

| 项 | 内容 |
|----|------|
| **变更** | `09`/`10` 部署文档改为双服务器拓扑；`.env.prod.example` 内网占位符与 Redis 预留；`nginx.conf.example` 应用层反代；项目总览双机架构展示 |
| **原因** | 与已确定的轻量+ECS 分层部署一致；公开文档不含真实 IP |
| **未改** | RAG 业务代码、Redis 业务接入、Debug/Evaluation 等页面 |

### V9-04：数据与检索层服务部署脚本准备

| 项 | 内容 |
|----|------|
| **变更** | `deploy/data-layer/*` Compose + README + `.env.data.example`；`scripts/check-data-layer.sh`；更新 09/10 文档与项目总览 |
| **原因** | ECS 上统一启动 PG / ES / Redis，ES JVM 适配 4C8G |
| **未改** | 业务 API、Redis 应用接入 |

### V9-05：应用入口层部署脚本准备

| 项 | 内容 |
|----|------|
| **变更** | `deploy/app-layer/*`；`deploy-backend-app.sh`、`deploy-frontend-app.sh`、`check-app-layer.sh`；09 文档与项目总览 |
| **原因** | 轻量服务器标准化部署 Nginx + 前端 + 后端 |
| **未改** | 业务 API、Debug/Evaluation 等页面 |

### V9-06：双服务器实机部署与联调验收 ✅

| 项 | 内容 |
|----|------|
| **变更** | `docs/11-dual-server-online-runbook.md`（含 ES 9200 安全组 FAQ）；`verify-online-deployment.sh`、`init-online-data.sh`、`start-data-layer.sh`；私有笔记模板；09 文档与项目总览线上状态 |
| **实机** | ECS 数据层 PG+ES 运行；轻量应用层 Nginx+后端+前端；ES 经 SSH 隧道；`verify-online-deployment.sh` 5 PASS；样例 KB 初始化 |
| **原因** | V9 实机部署与联调验收标准化 |
| **未改** | 业务 API；真实 IP/Key 不提交仓库 |

---

## 后续版本（占位，未实现）

| 版本 | 计划核心变更 |
|------|----------------|
| V8+ | 权限、多租户、Recall@K / MRR 等 |

**禁止**在未达对应版本前写入「已完成」或提前建表/接口。
