# 模型 & 成本中心 —— E2E 测试用例设计（Playwright）

> 角色：交付给 Cursor 在云服务器上用 Playwright 执行。本文件只设计用例，不含实现代码。
> 覆盖目标：鉴权、列表渲染、停用守卫、回退链、持久化、并发、数据一致性、空态、文案与计价。
> 重点深挖：停用守卫的 409 友好提示与**开关回滚**、**toast 不得重复/不得暴露原始错误码**。

---

## 0. 测试环境与前置

| 项 | 要求 |
|----|------|
| 部署 | 云服务器，前端 + 后端 + PostgreSQL 正常启动 |
| 数据库迁移 | **V37/V38 必须已落库**（价格校准 + jina 排序）；否则 TC-02/TC-10 计价与排序断言会失败 |
| 账号 | 一个 `ADMIN` 账号、一个非 ADMIN（普通）账号 |
| 入口 | 前端路由 `/models`；后端 `'/api/v1/models'`，全部 `@PreAuthorize("hasRole('ADMIN')")` |
| 数据准备 | 看板类用例（TC-08）需当月有 `model_usage_daily` 数据；可执行 `db/manual/backfill_model_usage_daily.sql` 注入基线，或在空态下走 TC-09 |
| 隔离性 | TC-03/05/06/07 会改 `model_config.enabled`，**每个用例结束必须把模型状态复原**（teardown 调用 toggle 回到初始态），避免相互污染 |

### 选择器参考（基于当前 DOM）

| 元素 | 选择器 |
|------|--------|
| Tab（模型管理/成本看板/用户配额） | `.tabs .tab`（按文本定位） |
| 模型表格行 | `.card .data-table tbody tr`（每行含 `.m-name` 模型名） |
| 行内开关 | 该行 `.sw input[type=checkbox]` |
| 状态徽章 | 该行 `.badge-green`（运行中）/ `.badge-gray`（已停用） |
| 计价单元格 | 该行 `td.price` |
| 价格列文本 | 该行第 3 个 `td`（`本地 · 无 API 费` / `¥x` / `¥x / ¥y`） |
| toast 容器 | `.toast-stack`；单条 `.toast-stack .toast` |
| 错误 toast 文本 | `.toast-error .toast-message` |
| 成功 toast 文本 | `.toast-success .toast-message` |
| KPI 卡片值 | `.kpi-row .kpi .kpi-val`（顺序：接入模型 / 本月费用 / 本月 Token / 调用次数） |
| 配额提示 | `section .note` |

> 当前页面无 `data-testid`。若文本/结构选择器出现 flaky，允许 Cursor 为关键节点补 `data-testid`（开关、toast、KPI），但**不得改动业务逻辑**。

### 禁止项（执行约束）

- ❌ 不得直接写库改 `enabled` 来制造前置；状态变更一律走 UI 开关或 `PUT /toggle` 接口，以真实覆盖守卫逻辑。
- ❌ 不得用 `waitForTimeout` 固定 sleep 兜断言；用 `expect(...).toHaveText/toBeVisible` 自动等待。
- ❌ 断言 toast 时不得只判断"存在某条"，必须**同时校验 toast 总数**，防止重复弹窗回归。
- ❌ 用例之间不得依赖执行顺序；每个用例自带 setup/teardown。

---

## 测试用例

### TC-01 路由鉴权：非 ADMIN 不可访问
- **目标 / 深挖**：越权访问；前端隐藏与后端拦截是否双重生效。
- **步骤**：
  1. 以非 ADMIN 登录，断言侧栏无「模型 & 成本中心」入口。
  2. 直接访问 `/models`。
  3. 直接请求 `GET /api/v1/models`（带普通用户 token）。
- **预期**：侧栏不显示该项；直达路由被守卫拦截（重定向/无内容，不报 JS 错）；接口返回 403。
- **Playwright 断言**：`expect(sidebar).not.toContainText('模型')`；接口响应 `status===403`。

### TC-02 列表渲染、排序与价格三态
- **目标 / 深挖**：渲染完整性、排序、价格格式分支（本地 / 单价 / 双价）、备用标签。
- **步骤**：进入「模型管理」，读取全部行。
- **预期**：
  - 行数与 `model_config` 一致（当前 7 行）。
  - **jina-reranker-v3 排在最后一行**（V38），且行内含「备用」标注、价格列显示 `本地 · 无 API 费`。
  - `qwen3-rerank` 价格列显示**单价** `¥0.50`（output_price=0 不显示 `/ ¥0`）。
  - `qwen-vl-ocr` 显示**双价** `¥6.00 / ¥6.00`。
  - 停用模型行有 `tr.off` 透明度样式 + `○ 已停用` 徽章。
- **断言**：`tbody tr` 末行 `.m-name` === `jina-reranker-v3`；逐行比对价格列文本。

### TC-03 停用守卫（核心）：停用某用途唯一可用模型被拒
- **目标 / 深挖**：截图 bug 的回归——409 守卫、友好提示、**开关回滚**、刷新后状态不变。
- **前置**：`ANSWER` 用途当前仅 `qwen-plus` 启用且无启用备用。
- **步骤**：
  1. 点击 `qwen-plus` 行开关尝试停用。
  2. 等待响应。
  3. 刷新页面。
- **预期**：
  - 出现**一条** error toast，文案为 **「停用失败：「答案生成」环节将没有可用模型，请先为该用途启用一个备用模型」**（不得出现原始码 `MODEL_DISABLE_WOULD_LEAVE_PURPOSE_UNAVAILABLE:ANSWER`）。
  - 开关**回弹为启用**，状态徽章仍为 `● 运行中`。
  - 刷新后 `qwen-plus` 依旧启用（后端未落库）。
- **断言**：`expect(.toast-error .toast-message).toHaveText(友好文案)`；`expect(.toast-message).not.toContainText('MODEL_DISABLE')`；开关 `toBeChecked()`。

### TC-04 toast 唯一性回归：不得双弹、不得露原始码
- **目标 / 深挖**：本次修复点——拦截器 + 组件曾各弹一条导致重复。
- **步骤**：复用 TC-03 触发 409，捕获 toast 期间的最大并存数量。
- **预期**：同一时刻 `.toast-stack .toast` **数量恰为 1**；其中无任何 `:ANSWER`、无 `UNAVAILABLE`、无大写下划线原始码片段。
- **断言**：`expect(page.locator('.toast-stack .toast')).toHaveCount(1)`；正则断言 message 不匹配 `/[A-Z_]{6,}/`。

### TC-05 RERANK 回退链：fallback 可解析才允许停用
- **目标 / 深挖**：`purposeStillResolvableWithout` 与 partial unique index 的真实行为。
- **步骤**：
  1. 初始 `qwen3-rerank` 启用主、`jina` 停用 → 停用 `qwen3-rerank`：应被**拒绝**（RERANK 将无可用模型）。
  2. 先启用 `jina`，再停用 `qwen3-rerank`：应**成功**（回退到 jina）。
  3. teardown：复原（停用 jina、启用 qwen3-rerank）。
- **预期**：步骤1 友好提示「精排…无可用模型」并回滚；步骤2 成功 toast「已停用 qwen3-rerank」，RERANK 仍可解析。
- **断言**：两步分别校验 toast 类型与状态徽章变化。

### TC-06 正常停用/启用 + 持久化
- **目标 / 深挖**：成功路径与 DB 持久化（乐观更新是否与后端一致）。
- **步骤**：选一个停用后仍有备用/不破坏用途的模型（如 TC-05 启用 jina 后停用 qwen3-rerank），停用→刷新→再启用→刷新。
- **预期**：每步出现对应 success toast（`已停用 X` / `已启用 X`）；**刷新后状态持久**（与点击后一致，非回弹）；看板「接入模型」KPI 的启用数同步变化。
- **断言**：reload 后 `input.toBeChecked()/not`；KPI 启用数前后差 1。

### TC-07 幂等与并发锁：快速双击不重复提交
- **目标 / 深挖**：`toggling` 锁；重复点击/抖动是否产生双请求或状态错乱。
- **步骤**：
  1. 监听 `PUT /toggle` 请求次数，对同一行**快速连点两次**。
  2. 对已处于目标态的模型再次 toggle（幂等）。
- **预期**：连点期间最多发出 **1 次** toggle 请求（锁生效，开关在 pending 时 disabled）；幂等 toggle 后端返回成功且状态不变、不报错。
- **断言**：拦截网络计数 `===1`；无 error toast。

### TC-08 成本看板数据一致性
- **目标 / 深挖**：聚合口径 bug（求和、百分比、成功率、堆叠高度）。
- **前置**：当月已有用量数据（注入基线）。
- **步骤**：进入「成本看板」，读取 KPI、排行、趋势、明细。
- **预期**：
  - KPI「接入模型」启用数 == 「模型管理」中 `● 运行中` 行数。
  - KPI「本月费用」== 排行各项费用之和（容差 ¥0.01，注意四舍五入）。
  - 排行各项 `pct` 之和 ≈ 100%（仅有费用项时）。
  - 趋势某日各堆叠段之和 == 该日总费用；无费用日柱高为 0、不渲染段。
  - 成功率在 `[0,1]`，无 `NaN`。
- **断言**：数值比对带容差；正则排除 `NaN`/`undefined`/`¥NaN`。

### TC-09 空数据态
- **目标 / 深挖**：无当月数据时的兜底渲染与除零。
- **前置**：当月 `model_usage_daily` 为空。
- **步骤**：进入「成本看板」。
- **预期**：排行区显示「本月暂无费用数据」；明细表显示「本月暂无调用明细」；KPI 费用为 `¥0.00`、Token `0`、**成功率显示为 100.0%**（calls=0 兜底）、趋势无柱；页面无 JS 报错、无 `¥NaN`。
- **断言**：`toContainText('暂无')`；监听 console error 为空。

### TC-10 配额文案 + 计价准确性
- **目标 / 深挖**：文案口径（企业级、无演示字眼）+ 官方计价落地。
- **步骤**：
  1. 切到「用户配额」Tab。
  2. 回「模型管理」核对计价。
- **预期**：
  - 配额提示精确为 **「🚧 用户配额功能待开发」**（不含"单实例/多租户/演示/demo"等多余文案）。
  - 计价与官方一致：`qwen-vl-ocr ¥6.00 / ¥6.00`、`qwen3-rerank ¥0.50`、`qwen-turbo ¥0.30 / ¥0.60`、`qwen-plus ¥0.80 / ¥2.00`、`deepseek-v4-flash ¥1.00 / ¥2.00`、`qwen3-vl-embedding ¥0.70`、`jina 本地 · 无 API 费`。
- **断言**：`expect(.note).toHaveText('🚧 用户配额功能待开发')`；逐模型价格文本精确匹配。

---

## 通过标准（验收）

1. 10 个用例全部独立可重复执行（含 teardown 复原），无相互污染。
2. TC-03/TC-04 必须证明：**单条** 友好中文 toast，且**无原始错误码**泄漏。
3. 任一用例执行期间，浏览器 console 无 `error` 级日志、页面无 `NaN/undefined` 文本。
4. 状态类用例需验证**前端乐观更新与后端持久化一致**（刷新后不回弹、不漂移）。
5. 产出 Playwright HTML 报告 + 失败截图/trace，附每条用例实际值 vs 预期值。
