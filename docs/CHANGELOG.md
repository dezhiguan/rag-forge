# RAGForge 版本演进时间线

> 本时间线从设计文档与代码迁移历史梳理而来,用于理解项目演进脉络。
> **注意:版本号有两条相互独立的轴** ——
> - **平台大版本** `V4 → V5 → V6`:架构与能力的整体迭代。
> - **特性设计迭代** `V1 → V2`:认证 / 权限 / 去租户这一组特性的局部迭代。
>
> 不要把 `unified-auth-V1` 的 V1 当成早于平台 `V5`。两条轴在同一时期并行推进。

---

## 平台主线

### 原型期(平台 V1 设计)
- `prototype/rag-service-design.html` — 0→1 原始设计「RAG 知识引擎」。
- 当时技术栈:Spring Boot 3.2 / Java 17、reranker = 本地 bge-reranker-v2-m3、embedding = text-embedding-v4、docker-compose + 本地磁盘。
- ⚠️ 这是当前**最过时**的口径源头;现状已全面演进(见 [architecture.md](architecture.md))。

### V4
- `prototype/RAGForge-架构设计文档-V4.html` — 平台架构 V4 设计稿,已被 V5 取代(历史归档)。

### V5(主线收口)
- `prototype/RAGForge-优化设计文档-V5.html` — V5 优化设计,提出多模态、检索质量等方向。其中**外置 OCR / 图片描述 chunk / 多 topic / 按模态拆 Worker** 等方案后被废弃。
- [architecture.md](architecture.md) — **V5 真实落地口径(权威)**:k3s、Java 21 / Spring Boot 3.5、统一 `vl_vector(2560)` 多模态向量空间、`qwen-vl-ocr` + `qwen3-vl-embedding`、单一 RocketMQ topic。
- 执行与任务:`dev/tasks.md`、`dev/v5-execution-tasks.md`(T1→T12)。关键里程碑:
  - T1 OSS 存储 SPI、T2 身份迁移、T3 Ingest、T4/T5 双通道上传、T6 重处理、T7 citations、T8 数据清洗、T9 Chunker、**T10 多模态统一向量空间**、**T11 Answer-as-LLM(`/api/v1/answer` SSE)**、T12 文档/多实例/监控收口。
- 验收:`test/v5-acceptance-playwright-cases.md`、`test/v5-acceptance-t11-summary.md`、`test/T11-headed-test-report.md`、`deploy/grafana-v5.json`。

### V6(评测增强)
- `dev/v6-llm-judge-execution-prompts.md` — 引入 **LLM-as-Judge**:DeepSeek 充当裁判,离线 Golden Set(每天 100 题)+ 在线 1% 抽样,质量看板内置(不依赖 Grafana),新增 `role=judge` Deployment(J1→J7)。
- `test/v6-stuck-running-recovery.sql` — Judge 卡 RUNNING 行的一次性排障修复脚本。
- V5→V6 取舍(Fault Injection S1-S4、LLM-as-Judge、inline prod 护栏、账单对账)记录在 `dev/v5-execution-tasks.md` 末尾。

---

## 横切:模型成本中心(V5/V6 期间)
- `dev/model-cost-center-design.md` — 把硬编码模型收口为注册表(`model_config`)+ 成本看板(`model_usage_daily`)+ 配额占位;确认 RERANK 实走 DashScope `qwen3-rerank`。
- 配套:`test/model-cost-center-test-cases.md`。
- 对应迁移:`V35`(建表+种子)、`V36`(rerank 主备修正)、`V37`(单价校准)、`V38`(jina 排序置末)、`V50`(org_id 成本分摊)。

---

## 特性线:认证 / 权限 / 去租户(独立 V1 → V2 → V3)

### V1
- `prototype/unified-auth-redesign-V1.html` — 统一认证与权限优化设计稿(CareerMate × RAGForge × Auth Gateway)。
- `test/unified-auth-test-plan-V1.md`、`test/org-permissions-test-plan-V1.md`、`test/org-view-test-plan-V1.md`、`test/retrieval-quality-test-plan-V1.md`。
- 早期模型:个人空间 `org_id=null`、**保留 `tenant_id`**。

### V2(决策反转)
- `dev/tenant-removal-and-org-permissions-V2.md` — **彻底移除 `tenant_id`**,改 GitHub 式「个人 + 组织」模型(组织落 RAG 本地,KB 用 `owner_user_id` / `org_id`)。取代 V1。
- 落地:`prototype/permission-plan.html`(从按平台控权 → 组织自治)、`test/org-permission-test-plan-V2.md`(取代 V1)。
- 对应迁移:`V45`(建 organizations/org_members,KB 加 org_id、删 tenant_id)、`V46`(邀请/通知)、`V48`(org type)、`V49`/`V51`(retrieval_log / api_key 加 org_id)。

### V3(会话续期加固 + 记住我 30 天,2026-07-03)
- 背景:线上频繁误报"登录已过期"(access token 仅 15min + 续期失败不分级把网络抖动当会话过期 + refresh 一次性旋转无宽限期误杀多标签页 + "记住我 30 天"后端未实现)。
- 网关(auth-gateway `feature/refresh-grace-remember`):旋转宽限期 60s(窗口内复用=并发双刷补发,超窗=重放灭族)、`remember` → 30 天 refresh TTL(滑动窗口,迁移 `V10` 落 `auth_sessions.refresh_ttl_seconds`)、响应新增 `refresh_expires_in`。
- 本仓库(`feature/auth-session-hardening`):DTO 透传 remember、cookie TTL 跟随网关、`/me` 改真 401;前端新增 `api/session.js`(主动续期提前 90s、Web Locks 跨标签页单飞、BroadcastChannel 同步、失败分级仅 401/403 踢登录),authClient/upload 补 401 重放,SSE 跟随 token 重建。
- 文档:`dev/security-and-multitenancy.md` §8、验收:`test/auth-session-hardening-acceptance-V1.md`。
- 遗留:`rf_csrf` 头后端未校验(靠 SameSite=Lax 兜底),待补。

---

## 与"当前事实"明显不符、已标注为历史的文档
- `prototype/rag-service-design.html`、`prototype/RAGForge-架构设计文档-V4.html` — 旧架构/旧技术栈,历史归档。
- `prototype/RAGForge-优化设计文档-V5.html` — 含已废弃方案,**以 architecture.md 为准**。
- `dev/cursor-prompts.md` — 0→1 历史 prompt(写 SB3.2/Java17/bge),仅作历史参考。
- `deploy/deployment-three-tier.md`、`deploy/deployment-migration-runbook.md`、`deploy/deployment-app-cluster.md` — docker-compose 三层形态,**以 `deploy/deployment-architecture.md`(k3s)为准**。
- `dev/security-and-multitenancy.md` — 仍含 `tenant_id` 叙述,按 V2 理解(tenant 已移除)。
