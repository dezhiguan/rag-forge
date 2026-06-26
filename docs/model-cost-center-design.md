# 模型 & 成本中心 —— 完整设计方案

> 实现参考视觉稿：`/Users/amy/Desktop/model-cost-center.html`
> 本文档是给 Cursor / Sonnet 的实现规格。**架构已定，按此实现，不要自由发挥偏离字段与接口契约。**
> 角色分工：本设计由架构师产出；Cursor 负责编码。

---

## 0. 一句话定位

把当前**散落在 yml 和代码里硬编码的 7 个模型**收口成一张可管理的注册表，提供：
1. **模型管理**：启用/停用、查看用途与计价（开关即时生效，停用后回退备用模型）。
2. **成本看板**：基于已有的 Token 计量，按模型/用途/日期统计费用。
3. **用户配额**：仅做 Roadmap 占位页，**本期不实现任何后端逻辑**。

## 0.1 关键前提（必须先读，否则会重复造轮子）

**Token 计量已经存在，不要新建计量逻辑。** 现有 `com.ragforge.metrics.RagforgeMetrics` 已在每个模型调用点用 Micrometer 记录 Token：

| 调用点 | 现有方法 | 对应模型/用途 |
|--------|---------|--------------|
| `DashScopeVlEmbeddingClient` | `recordVlEmbeddingCall` / `recordVlEmbeddingImageTokens` | qwen3-vl-embedding / EMBEDDING |
| `RemoteOcrClient` | `recordOcrCall(imageTokens, outputTokens)` | qwen-vl-ocr / OCR |
| `AnswerService` | `recordAnswerTokens(prompt, completion)` | qwen-plus / ANSWER |
| `JudgeOrchestrator` | `recordDeepSeekTokens(...)` + `recordJudgeCost(...)` | deepseek-v4-flash / JUDGE |
| Query 改写 (`LlmServiceImpl`) | 目前**未计量** | qwen-turbo / REWRITE → 本期补一处 record |
| Reranker (`jina-reranker-v3`) | 本地无 Token 费 | RERANK → 只记调用次数 |

**实现策略 = 在这些已有 record 调用点旁边，并联一个 `ModelUsageRecorder.record(event)`，把同一份 Token 数据异步写入成本库。** 不改动检索/应答主链路的同步逻辑。

---

## 1. 数据库设计（PostgreSQL · Flyway）

当前最新迁移版本 `V34`，新增迁移从 **V35** 开始。共 2 个迁移文件。

### 1.1 `V35__model_cost_center.sql` —— 建表 + 种子数据

#### 表一：`model_config`（模型注册表，驱动 UI + 运行时解析）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGSERIAL PK | |
| `code` | VARCHAR(64) UNIQUE NOT NULL | 模型唯一标识，如 `qwen3-vl-embedding`、`deepseek-v4-flash` |
| `display_name` | VARCHAR(128) NOT NULL | 展示名（可与 code 相同） |
| `vendor` | VARCHAR(64) NOT NULL | 厂商：`dashscope` / `deepseek` / `local` |
| `purpose` | VARCHAR(32) NOT NULL | 用途枚举：`EMBEDDING`/`OCR`/`REWRITE`/`RERANK`/`ANSWER`/`JUDGE` |
| `endpoint` | VARCHAR(255) | 调用端点（仅展示/备注，运行时仍读 yml，见 §4.1） |
| `input_price` | NUMERIC(10,4) NOT NULL DEFAULT 0 | 输入单价，单位 **元/百万 Token** |
| `output_price` | NUMERIC(10,4) NOT NULL DEFAULT 0 | 输出单价，元/百万 Token；embedding/local 为 0 |
| `is_local` | BOOLEAN NOT NULL DEFAULT false | 本地自托管（无 API 费） |
| `enabled` | BOOLEAN NOT NULL DEFAULT true | 启用/停用开关 |
| `is_primary` | BOOLEAN NOT NULL DEFAULT true | 同一 purpose 下是否为首选；false=备用 |
| `fallback_code` | VARCHAR(64) | 停用时回退到的模型 code（可空） |
| `sort_order` | INT NOT NULL DEFAULT 0 | UI 排序 |
| `created_at` / `updated_at` | TIMESTAMPTZ DEFAULT now() | |

约束：`UNIQUE(code)`；建议**部分唯一索引**保证每个 purpose 只有一个 enabled 的 primary：
`CREATE UNIQUE INDEX uq_model_primary ON model_config(purpose) WHERE is_primary AND enabled;`

**种子数据（7 行，对应当前真实模型与价格，价格以实现时 DashScope/DeepSeek 官网为准）：**

| code | display_name | vendor | purpose | input_price | output_price | is_local | enabled | is_primary | fallback_code |
|------|--------------|--------|---------|-------------|--------------|----------|---------|-----------|---------------|
| qwen3-vl-embedding | qwen3-vl-embedding | dashscope | EMBEDDING | 0.70 | 0 | f | t | t | null |
| deepseek-v4-flash | deepseek-v4-flash | deepseek | JUDGE | 1.00 | 2.00 | f | t | t | null |
| qwen-turbo | qwen-turbo | dashscope | REWRITE | 0.30 | 0.60 | f | t | t | null |
| qwen-plus | qwen-plus | dashscope | ANSWER | 0.80 | 2.00 | f | t | t | null |
| jina-reranker-v3 | jina-reranker-v3 | local | RERANK | 0 | 0 | t | t | t | qwen3-rerank |
| qwen3-rerank | qwen3-rerank | dashscope | RERANK | 1.50 | 0 | f | f | f | null |
| qwen-vl-ocr | qwen-vl-ocr | dashscope | OCR | 5.00 | 0 | f | f | t | null |

> 注：`jina-reranker-v3`(primary, enabled) + `qwen3-rerank`(备用, disabled) 显式表达了之前 flag 的"双精排路线"；`qwen-vl-ocr` 默认停用对应当前 `RAGFORGE_MULTIMODAL_ENABLED` 实际状态，按你环境调整。

#### 表二：`model_usage_daily`（日级汇总，看板主数据源）

按 `(model_code, stat_date)` 聚合，UPSERT 写入，看板查询走这里（快）。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGSERIAL PK | |
| `model_code` | VARCHAR(64) NOT NULL | |
| `purpose` | VARCHAR(32) NOT NULL | 冗余存储便于按用途聚合 |
| `stat_date` | DATE NOT NULL | 统计日（按服务器时区） |
| `call_count` | BIGINT NOT NULL DEFAULT 0 | |
| `input_tokens` | BIGINT NOT NULL DEFAULT 0 | |
| `output_tokens` | BIGINT NOT NULL DEFAULT 0 | |
| `cost` | NUMERIC(12,4) NOT NULL DEFAULT 0 | 元 |
| `success_count` / `fail_count` | BIGINT DEFAULT 0 | 成功率用 |
| `total_latency_ms` | BIGINT DEFAULT 0 | 配合 call_count 算均值 |
| `updated_at` | TIMESTAMPTZ DEFAULT now() | |

约束：`UNIQUE(model_code, stat_date)` —— UPSERT 冲突键。索引 `(stat_date)`、`(model_code, stat_date)`。

#### 表三（可选）：`model_usage_log`（原始调用流水，用于钻取）

**仅对低频 LLM 调用（REWRITE/ANSWER/JUDGE/OCR）落原始流水；EMBEDDING/RERANK 高频，只进日汇总，不落流水**（百万级 chunk 会撑爆表）。字段：`id, model_code, purpose, input_tokens, output_tokens, cost, latency_ms, success, biz_ref(如 traceId/docId), created_at`。带 `created_at` 索引，配 §4.4 保留策略。
> 本期可先**不建此表**，看板只靠 `model_usage_daily` 即可满足视觉稿全部图表。建议作为 P1。

### 1.2 `db/manual/backfill_model_usage_daily.sql`（可选 · 非生产环境基线数据）

为 dev / staging / 联调环境在真实流量积累前提供看板基线数据，向 `model_usage_daily` 写入近 30 天基线用量。**不进 Flyway 迁移路径**，放 `db/manual/` 由运维手工执行；**生产环境由 `ModelUsageRecorder` 实时落库，不执行此脚本**。

---

## 2. 后端设计（Java 17 · Spring Boot 3.2 · MyBatis-Plus）

包路径统一在 `com.ragforge.modelcenter`（新增模块，避免污染现有包）。

### 2.1 实体 / Mapper
- `ModelConfig`、`ModelUsageDaily` 两个实体（MyBatis-Plus `@TableName`），对应 Mapper 继承 `BaseMapper`。

### 2.2 运行时模型解析 —— `ModelResolver`（本设计的工程价值核心）

替换当前散落的硬编码（如 `AnswerService` 的 `.orElse("qwen-plus")`、yml 里的 `rewrite-model`）。

```
interface ModelResolver {
  ResolvedModel resolve(Purpose purpose);   // 返回当前生效模型
}
// 解析规则：
// 1. 查 purpose 下 enabled && is_primary 的模型 → 命中即用
// 2. 未命中 → 查该 primary 的 fallback_code，若其 enabled → 用 fallback
// 3. 仍无 → 抛 ModelUnavailableException(purpose)，由调用方决定降级/报错
```
- **缓存**：`model_config` 全量缓存在内存（Caffeine 或简单 `volatile Map`），**toggle 接口写库后主动刷新缓存**（单实例无需分布式失效；多实例留 TODO）。
- **接入范围（已定 · A 方案，不要扩大）**：
  - **本期做动态解析的只有 REWRITE、ANSWER 两个用途**——把这两处的硬编码（`AnswerService.orElse("qwen-plus")`、yml `rewrite-model`）改成 `resolver.resolve(...)`，让页面开关对这两者**真实生效**。
  - **EMBEDDING、OCR、RERANK、JUDGE 四个用途本期不接入动态解析**——保持现有调用代码原样不动，**只做计量并联**（见 2.4），开关在 UI 上为展示态。
  - 原因：EMBEDDING 切换会导致已入库向量维度/语义不一致、需全量重嵌，属高风险大工程；REWRITE/ANSWER 无状态、切换零历史包袱，最适合先跑通"开关切模型"能力。
  - 其余四个用途的完整动态解析留 **P1**，本期严禁顺手扩大改动范围。

### 2.3 计价 —— `CostCalculator`
```
cost = (inputTokens * input_price + outputTokens * output_price) / 1_000_000
```
单价来自 `model_config`（走 `ModelResolver` 缓存）。local 模型恒为 0。`JudgeOrchestrator` 已有的 `recordJudgeCost` 改为复用本计算器，避免两套口径。

### 2.4 计量并联 —— `ModelUsageRecorder`（异步、不阻塞主链路）

```
class ModelUsageEvent { String modelCode; Purpose purpose; long inputTokens; long outputTokens;
                        long latencyMs; boolean success; }
interface ModelUsageRecorder { void record(ModelUsageEvent e); }
```
实现要点：
- **非阻塞**：`record()` 只入内存队列（如 `ConcurrentLinkedQueue` 或有界 `BlockingQueue`，队列满则丢弃并打点，绝不阻塞业务线程）。
- **批量落库**：`@Scheduled(fixedDelay=5s)` 消费队列，按 `(model_code, stat_date)` 在内存聚合后，对 `model_usage_daily` 做 **UPSERT 累加**（`INSERT ... ON CONFLICT (model_code, stat_date) DO UPDATE SET call_count = call_count + EXCLUDED...`）。
- **接入方式**：在 §0.1 表格列出的每个现有 `metrics.recordXxx(...)` 调用点**旁边**加一行 `usageRecorder.record(...)`。REWRITE 处需新增计量（当前缺失）。
- **实现状态（本次交付）**：已并联计量的付费 LLM 路径 = **ANSWER / REWRITE / JUDGE**（chat-completion 调用，prompt/completion Token 精确可得，计价口径一致）。**EMBEDDING / OCR / RERANK 暂未并联**——其 Token 语义不同：embedding 的文本 Token 当前未在响应里捕获、OCR 走图像 Token、rerank 为本地零费；这三者需各自确认 API usage 字段后再接，作为 fast-follow，避免上报估算值污染成本口径。`ModelUsageRecorder` 为通用实现，补接仅是在对应调用点加一行 `record(...)`。
- **禁止**：不得在模型调用的同步路径里直接 `INSERT` 数据库。

> 备选方案：复用现有 RocketMQ 发计量事件。**本期不采用**——引入 MQ 收发两端、消费幂等、积压监控，对一个看板属过度设计。内存队列 + 定时 UPSERT 足够，数据延迟 ≤5s 对看板无感知影响。

### 2.5 REST API（`ModelCenterController`，前缀 `/api/v1/models`，权限 §5）

| 方法 | 路径 | 说明 | 返回 |
|------|------|------|------|
| GET | `/api/v1/models` | 模型列表（含本月费用汇总，列表页用） | `List<ModelItemVo>` |
| PUT | `/api/v1/models/{code}/toggle` | 启用/停用，body `{enabled:bool}`；触发缓存刷新 | `{code, enabled}` |
| GET | `/api/v1/models/cost/stats?days=7` | 趋势(按日按用途堆叠) + 费用排行 + 顶部 KPI | `CostStatsVo` |
| GET | `/api/v1/models/cost/detail` | 按用途的调用明细表 | `List<CostDetailVo>` |

**VO 字段契约**（前端 `api/model.js` 已按此约定，勿改名）：
- `ModelItemVo`：`code, displayName, vendor, purpose, inputPrice, outputPrice, isLocal, enabled, isPrimary, monthlyCost`
- `CostStatsVo`：
  - `kpi`: `{modelCount, enabledCount, monthlyCost, monthlyTokens(in/out), callCount, successRate}`
  - `trend`: `[{date, byPurpose:{EMBEDDING:.., JUDGE:.., ...}}]`（近 N 天）
  - `ranking`: `[{modelCode, cost, pct}]` 降序
- `CostDetailVo`：`purpose, modelCode, callCount, inputTokens, outputTokens, cost, avgLatencyMs`

**校验/禁止项**：
- toggle 停用 primary 且无可用 fallback 时 → 返回 `409 + 业务码`，前端弹确认/报错（不允许把链路改坏）。
- 价格字段只读（本期不开放改价 API，"同步价目"按钮先做占位/灰置）。
- 所有金额后端算好返回，前端只展示，**不在前端做计价**。

---

## 3. 前端设计（Vue 3 + Vite）

### 3.1 新增文件
- `frontend/src/views/ModelCostCenter.vue` —— 单页三 Tab，结构/配色严格对齐视觉稿。
- `frontend/src/api/model.js` —— **已创建**（getModelList / toggleModel / getModelCostStats / getModelCostDetail）。

### 3.2 路由（`router/index.js`）—— 在 `/api` 前插一条
```
{ path: '/models', name: 'ModelCostCenter', component: () => import('../views/ModelCostCenter.vue'),
  meta: { icon: '🧠', label: '模型 & 成本', role: 'ADMIN' } }
```
> 实现取舍：仅用 `role: 'ADMIN'` 守卫，**不挂自定义 scope**。后端 Controller 已用 `@PreAuthorize("hasRole('ADMIN')")` 强制，等价且更稳；避免新 scope 未被签发导致菜单被 `canAccessRoute` 误隐藏。

### 3.3 侧边栏（`components/Sidebar.vue` 的 `ALL_NAV_ITEMS`）—— 在「API 网关」前加：
```
{ path: '/models', icon: '🧠', label: '模型 & 成本', meta: { role: 'ADMIN' } }
```

### 3.4 页面结构（对齐视觉稿）
- **Tab1 模型管理**：4 个 KPI 卡 + 模型表格（启用开关用 `toggleModel`，停用行置灰）。复用全局 `.badge` / 表格样式（见 `App.vue` `<style>`，已有 `--navy/--blue/...` CSS 变量与 `.badge-green/.data-table`）。开关 toggle 走乐观更新 + 失败回滚 + Toast（项目已有 `ToastContainer`）。
- **Tab2 成本看板**：7 日堆叠柱状图 + 费用排行 + 调用明细表。柱状图**用纯 CSS div 实现**（视觉稿即如此），不引第三方图表库。
- **Tab3 用户配额**：**纯静态 Roadmap 占位**，照搬视觉稿那块（黄色提示条 + 三张规划卡），不调任何接口。

### 3.5 禁止项
- 不引入新 UI/图表依赖（ECharts 等）；柱状图/进度条用 CSS。
- 不在前端硬编码模型列表与金额——全部来自接口。
- 移动端沿用 `App.vue` 已有响应式规则（表格 `overflow-x:auto`）。

---

## 4. 与现有代码的衔接 & 一致性收口

| 现状问题（之前已 flag） | 本方案如何解决 |
|------------------------|---------------|
| `qwen-plus` 硬编码在 `AnswerService.orElse(...)` | 改走 `ModelResolver.resolve(ANSWER)`，配置进 `model_config` |
| 精排双路线（qwen3-rerank vs jina）未声明 | 注册表里 jina=primary/enabled、qwen3-rerank=fallback/disabled，关系显式化 |
| 模型散落 yml + 代码，无法运营 | 收口到 `model_config` 单一事实源 |
| REWRITE 无 Token 计量 | §2.4 补一处 `recordVl…`/`usageRecorder.record` |

**4.1 yml 不删除**：`application.yml` 里 endpoint/api-key 仍是运行时真实配置（密钥不入库）。`model_config` 管的是"用哪个模型/启停/计价"，不接管端点鉴权。两者职责分离。

**4.4 数据保留**：若实现可选的 `model_usage_log`，加 `@Scheduled` 每日清理 N 天前流水（默认 90 天）。`model_usage_daily` 永久保留（体量小）。

---

## 5. 权限

**仅 `ADMIN` 角色**可访问。后端 Controller 用 `@PreAuthorize("hasRole('ADMIN')")`（与现有 `JudgeSamplingController` 一致），前端路由/侧边栏用 `role: 'ADMIN'` 守卫。本期**不引入自定义 scope**（`rag:model:admin`）——因为新 scope 需在签发处登记否则会被 `canAccessRoute` 误拦；role 守卫已等价覆盖。若后续要更细粒度，再补 scope 并同步签发逻辑。

---

## 6. 部署方案

**无新增中间件**（不需要新数据库/MQ/服务）。

1. **DB**：Flyway 自动执行 V35。非生产环境基线数据脚本放 `db/manual/` 手工执行，生产不跑。
2. **后端**：随主应用发布；新增 `@Scheduled` 落库任务（确认主应用已开 `@EnableScheduling`，项目已有定时任务则无需改）。
3. **前端**：随 `frontend` 构建发布，新增一个路由 chunk，无需 nginx 改动。
4. **配置**：无新增环境变量。价格随种子数据入库，调价改库即可。
5. **回滚**：纯增量（新表 + 新接口 + 新页面），不改既有表结构；停用入口=移除侧边栏项即可，对现有功能零影响。
6. **多实例注意**（当前单实例可忽略，留 TODO）：`ModelResolver` 内存缓存在多副本下需广播失效（Redis pub/sub 或轮询版本号）。

---

## 7. 实施顺序（建议 Cursor 分任务提交）

1. **T1 DB**：V35 建表+种子（+可选 `db/manual` 基线脚本）。
2. **T2 后端读**：实体/Mapper + `GET /models` + `GET /cost/stats` + `GET /cost/detail`（先让看板能展示基线数据）。
3. **T3 后端写**：`toggle` 接口 + `ModelResolver`(含缓存刷新) + REWRITE/ANSWER 两处接入。
4. **T4 计量**：`ModelUsageRecorder` + 各调用点并联 + `@Scheduled` UPSERT。
5. **T5 前端**：`ModelCostCenter.vue` 三 Tab + 路由 + 侧边栏 + scope 登记。
6. **T6 验收**：见 §8。

---

## 8. 验收标准（Playwright E2E，遵循项目既有验收方式）

以 ADMIN 登录后：
1. 侧边栏出现「🧠 模型 & 成本」入口，点击进入 `/models`。
2. **Tab 模型管理**：表格渲染 ≥7 行；KPI 卡显示非空数值；点击某模型开关 → 该行状态在 ≤2s 内变更（停用→置灰），刷新页面状态持久（已落库）。
3. **停用 primary 无 fallback** → 弹错误/确认，链路不被改坏（后端返回 409）。
4. **Tab 成本看板**：柱状图渲染 7 根柱、费用排行有序、明细表行数=用途数；金额格式为 `¥xx.xx`。
5. **Tab 用户配额**：展示 Roadmap 占位与黄色提示条，无任何接口请求（断言无 `/api/v1/models/quota*` 调用）。
6. **非 ADMIN 角色**（KB_EDITOR/VIEWER）：侧边栏无此入口，直接访问 `/models` 被守卫拦截到 403。
7. **计量闭环**（接口级，可后端集成测试替代）：触发一次应答/改写 → ≤10s 后 `model_usage_daily` 当日对应 model_code 的 `call_count`、`cost` 增长。

---

## 9. 明确不做（本期边界）

- ❌ 用户配额的任何后端逻辑（限流/熔断/计费）——仅静态占位页。
- ❌ 在线改价 / 自动同步厂商价目——"同步价目"按钮占位灰置。
- ❌ EMBEDDING/RERANK 的原始流水落库——只进日汇总。
- ❌ 多实例缓存失效广播——单实例够用，留 TODO。
- ❌ 替换全部调用点的模型解析——**本期仅 REWRITE/ANSWER 两个用途做动态解析**；EMBEDDING/OCR/RERANK/JUDGE 只计量、开关为展示态，其余 P1（已定 A 方案，见 §2.2）。
