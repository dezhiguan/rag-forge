# RAGForge 组织视角端到端测试用例（Playwright）— V1

> 范围：本期覆盖 **质量看板**、**模型 & 成本** 两个模块。后续模块（驾驶舱 / 知识库 / 检索 / 应答 / 评测实验室 / 开发者中心 …）按相同编号规则追加到本文档。
> 视角：**组织（org）维度**——重点验证「当前组织看到的数据是否正确、是否只含本组织（+公开库）、切组织/破玻璃是否一致」。
> 说明：本文档只做**用例设计**，不含执行。每个小功能点用例数 ≥ 10。

---

## 0. 测试约定

### 0.1 角色（Persona）

| 代号 | 身份 | 关键属性 |
|---|---|---|
| **P-OWNER** | 团队组织 A 的 OWNER | 组织 A，orgRole=OWNER |
| **P-ADMIN** | 团队组织 A 的 ADMIN | 组织 A，orgRole=ADMIN |
| **P-MEMBER** | 团队组织 A 的普通成员 | 组织 A，orgRole=MEMBER，ragRole=USER |
| **P-PERSONAL** | 个人组织 owner | 个人组织，orgRole=OWNER，ragRole=USER |
| **P-SUPER** | 平台超管（默认在某组织上下文） | ragRole=ADMIN，未破玻璃 |
| **P-SUPER-PLAT** | 平台超管 + 已切「全平台视图」 | ragRole=ADMIN，破玻璃生效（X-Admin-Override） |
| **P-ORGB** | 另一组织 B 的 OWNER | 组织 B，用于隔离/越权对照 |

### 0.2 测试数据前置（seed，需可灌入已知值以便精确断言）

| 资源 | 说明 |
|---|---|
| 组织 A（团队） | KB-A1（私有，有 judge 数据 + 模型用量）、KB-A2（私有，无数据）、KB-PUB（**公开**，A 创建，有 judge 数据） |
| 组织 B（团队） | KB-B1（私有，有 judge 数据 + 模型用量，**数值与 A 不同**） |
| judge_results / judge_metrics_daily | 按 KB 灌入**已知**样本数、分数、成本、来源（PRODUCTION/GOLDEN_SET/MANUAL）、created_at/date |
| model_usage_daily | 按 **org_id** 灌入已知 input/output token、cost、call、success/fail；含 **org_id=0**（评测/未归属）一条 |
| 空态组织 | 组织 C（团队，无任何 judge 数据、无模型用量），用于空态断言 |

### 0.3 编号规则

`<模块前缀>-<小功能区>-<两位序号>`，例：`QD-OV-01`。
- 质量看板前缀 **QD**；模型 & 成本前缀 **MC**。
- 后续模块自取前缀（如 驾驶舱 DB、知识库 KB、检索 SE…），不与本文档冲突即可。

### 0.4 断言基线（贯穿全部用例的"隐藏 bug"维度）

1. **求和一致性**：总览/KPI 的合计 == 其明细分项之和（by-kb、costBySource、cost detail 行）。
2. **组织隔离**：当前组织绝不出现他组织（B）的数据；构造他组织 id 直接请求须 403。
3. **公开库纳入**：可访问范围 = 本组织库 ∪ 公开库（PUBLIC）。
4. **破玻璃聚合**：全平台视图 ≥ 任一单组织口径；含 org_id=0 的未归属成本。
5. **空态健壮**：无数据时显示 0 / —，**不得出现 NaN / null / undefined / ¥1.00 占位误显**。
6. **边界与精度**：days 边界、金额两位小数四舍五入、Token 大数格式化、成功率 0%/100%。
7. **切换一致性**：切组织/切时间范围后，同一页内多个区块（KPI、明细、趋势）口径联动一致。

---

# 模块一：质量看板（/evaluation/quality）

接口：`/overview`、`/by-kb`、`/worst-cases`、`/case/{id}`、`/cost`。组织范围 `currentOrgScope` = 本组织 KB ∪ 公开库；破玻璃=全平台；无组织上下文=空。

## QD-OV 概览 KPI 与样本统计

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| QD-OV-01 | 概览-KPI 均为本组织口径 | P-OWNER | 进质量看板，读概览 KPI | overallScore/faithfulness/contextPrecision/answerRelevance 均渲染，且等于组织 A 的已知灌入值 |
| QD-OV-02 | 概览样本数=各 KB 样本之和 | P-OWNER | 比对 overview.samples.totalSamples 与 by-kb 各行 sampleCount 求和 | 两者完全相等（求和一致性） |
| QD-OV-03 | 失败率计算正确 | P-OWNER | 读 samples.failedSamples/totalSamples 与 failedRate | failedRate == failed/total，四舍五入正确，分母 0 时不报错 |
| QD-OV-04 | 三项细分指标与案例均值吻合 | P-OWNER | 对比 KPI 的 faithfulness/contextPrecision/answerRelevance 与样本明细均值 | 偏差在容差内，无错位（如把 precision 显示成 relevance） |
| QD-OV-05 | 空态组织概览不报 NaN | P-MEMBER(组织C) | 切到无数据组织 C 看概览 | KPI 显示 0 / —，无 NaN/null，页面不崩 |
| QD-OV-06 | 变化值方向正确 | P-OWNER | 读各 *Change 字段符号 | 升降方向与相邻周期数据一致，正负号正确 |
| QD-OV-07 | P95 延迟为非负整数毫秒 | P-OWNER | 读 retrievalLatencyP95Ms | 整数、≥0、与样本延迟分布吻合 |
| QD-OV-08 | 切组织概览数值随之变化 | P-OWNER→ 切 B（若有权）/P-SUPER | A 与 B 概览对比 | 两组织 KPI 数值不同，互不串 |
| QD-OV-09 | 概览不含他组织样本 | P-OWNER | A 概览样本数 vs 已知 A 灌入值 | 严格等于 A，不含 B 的任何样本 |
| QD-OV-10 | 公开库样本计入本组织概览 | P-OWNER | KB-PUB 有数据时看概览 | 概览样本含 KB-PUB；移除公开库数据后概览相应减少 |
| QD-OV-11 | 破玻璃概览为全平台聚合 | P-SUPER-PLAT | 全平台视图看概览 | 样本数 ≥ 组织 A、≥ 组织 B，约等于全平台总量 |
| QD-OV-12 | 概览 costLastPeriodCny 与成本页一致 | P-OWNER | 概览成本字段 vs /cost totalCny | 同口径下两处一致或可解释（周期定义一致） |

## QD-TR 时间范围筛选（days）

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| QD-TR-01 | 默认时间范围为 7 天 | P-OWNER | 不带参数进入 | days 默认 7，概览/趋势按 7 天 |
| QD-TR-02 | 7→30 天样本单调不减 | P-OWNER | 切 7 天、30 天对比 | 30 天 totalSamples ≥ 7 天（更长周期不少于短周期） |
| QD-TR-03 | days=1 仅统计当天 | P-OWNER | 设 days=1 | 仅含当天 created_at/date 的样本 |
| QD-TR-04 | days=0 回退默认不报错 | P-OWNER | 构造 days=0 | 后端 normalizeDays 钳制，返回 200，不报错 |
| QD-TR-05 | days 负数被钳制 | P-OWNER | days=-5 | 钳制到合法值，无异常 |
| QD-TR-06 | days 超大不超时不溢出 | P-OWNER | days=3650 | 200 返回，趋势点数合理，无整型溢出 |
| QD-TR-07 | 趋势点数随天数变化 | P-OWNER | 切 7/30 看 trend.length | 点数与天数对应，无缺点/重复点 |
| QD-TR-08 | 成本 days 与概览 days 解耦 | P-OWNER | 概览 days=7、成本 days=30 | 两区块各用各的参数，互不影响 |
| QD-TR-09 | 跨月边界归集正确 | P-OWNER | 选含月初的区间 | 数据按日期正确落入区间，无跨月丢失 |
| QD-TR-10 | days 切换同步到 URL 且刷新保持 | P-OWNER | 切 days 后看 URL query、刷新 | query.days 同步，刷新后保持选择 |
| QD-TR-11 | days 切换联动刷新三区块 | P-OWNER | 切 days | 概览/by-kb/worst-cases/cost 同步刷新为新区间 |
| QD-TR-12 | 时区边界当天数据不漏不重 | P-OWNER | 临界时间灌入样本 | 当天最新样本计入一次且仅一次 |

## QD-KB 按知识库切片与 KB 筛选

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| QD-KB-01 | by-kb 列出本组织有数据的 KB | P-OWNER | 看 by-kb 列表 | 含 KB-A1、KB-PUB；不含无数据的 KB-A2（或显示 0） |
| QD-KB-02 | by-kb 不含他组织 KB | P-OWNER | 检查列表 kbId | 绝无 KB-B1 |
| QD-KB-03 | by-kb 含公开库 | P-OWNER | 检查列表 | 含 KB-PUB |
| QD-KB-04 | by-kb 样本之和=概览总样本 | P-OWNER | 求和对比 | Σ sampleCount == overview.totalSamples |
| QD-KB-05 | kbId 筛选=本组织 KB 生效 | P-OWNER | overview?kbId=KB-A1 | 仅返回 KB-A1 口径数据 |
| QD-KB-06 | kbId=他组织 KB 越权拦截 | P-OWNER | overview?kbId=KB-B1 | 403 KB_ACCESS_DENIED，不泄漏任何 B 数据 |
| QD-KB-07 | kbId=不存在 KB | P-OWNER | overview?kbId=999999 | 403/404，不报 500 |
| QD-KB-08 | kbId=公开库正常 | P-OWNER | overview?kbId=KB-PUB | 正常返回该公开库口径 |
| QD-KB-09 | by-kb overallScore 展示与排序 | P-OWNER | 看分数列 | 分数在合法区间，排序稳定 |
| QD-KB-10 | by-kb trend 为环比变化 | P-OWNER | 看 trend 字段 | 体现相对上一周期的升降，符号正确 |
| QD-KB-11 | 空组织 by-kb 返回空列表非 null | P-MEMBER(组织C) | 看 by-kb | 返回 []（非 null），UI 显示空状态 |
| QD-KB-12 | kbId 筛选联动 worst-cases | P-OWNER | 选 KB-A1 后看 worst-cases | 最差案例同样限定 KB-A1 |

## QD-WC 最差案例与案例详情

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| QD-WC-01 | 默认 limit=10 | P-OWNER | 看 worst-cases | 最多返回 10 条 |
| QD-WC-02 | 按分数升序（最差在前） | P-OWNER | 看排序 | overallScore 升序，最低分置顶 |
| QD-WC-03 | limit=1 边界 | P-OWNER | limit=1 | 仅返回最差 1 条 |
| QD-WC-04 | limit 超大不报错 | P-OWNER | limit=10000 | 返回全部可用，不异常 |
| QD-WC-05 | 仅含本组织案例 | P-OWNER | 检查每条所属 KB | 无 B 的案例 |
| QD-WC-06 | 含公开库案例 | P-OWNER | 检查 | KB-PUB 的差案例可出现 |
| QD-WC-07 | 点案例进详情 id 一致 | P-OWNER | 点列表项→详情 | 详情 judgeResultId 与列表项一致 |
| QD-WC-08 | 详情越权拦截 | P-OWNER | 直接访问 /case/{B的judgeResultId} | 403 KB_ACCESS_DENIED |
| QD-WC-09 | 详情字段渲染完整 | P-OWNER | 看详情 | query/answer/chunks/scores/improvements/bottleneck/judgeReasoning 均渲染 |
| QD-WC-10 | 详情 scores 与列表分数吻合 | P-OWNER | 对比 | 详情 scores 聚合与列表 overallScore 一致 |
| QD-WC-11 | topIssue 文案非空 | P-OWNER | 看列表 topIssue | 每条有可读的问题归因，非空/非占位 |
| QD-WC-12 | 空组织最差案例空状态 | P-MEMBER(组织C) | 看 worst-cases | 空列表 + 友好空状态 UI |

## QD-CO 成本汇总与按来源分摊

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| QD-CO-01 | totalCny=本组织 judge 成本 | P-OWNER | 读 /cost totalCny | 等于组织 A（含公开库）已知成本之和 |
| QD-CO-02 | 日均=total/days | P-OWNER | 读 dailyAverageCny | == totalCny / days，四舍五入两位 |
| QD-CO-03 | 月度预估=日均×30 | P-OWNER | 读 monthlyProjectedCny | == dailyAverage × 30 |
| QD-CO-04 | 调用/失败数正确 | P-OWNER | 读 totalCalls/failedCalls | 与灌入样本一致，failed ≤ total |
| QD-CO-05 | 按来源求和≈总额 | P-OWNER | Σ costBySource(三类) 对比 totalCny | 三类之和约等于（或可解释差异）总额 |
| QD-CO-06 | 无调用占位显示¥0.00（回归） | P-MEMBER(组织C) | 空态看成本来源 | 显示「无调用 ¥0.00」，**不得是 ¥1.00**；灰条满宽 |
| QD-CO-07 | 空组织成本全 0 | P-MEMBER(组织C) | 看成本 KPI | total/日均/月度/calls 全 0，无 NaN |
| QD-CO-08 | 切组织成本变化 | P-SUPER | A→B 切换 | 成本数值随组织变化 |
| QD-CO-09 | 组织 A 成本不含 B | P-OWNER | 比对已知值 | 严格等于 A，无 B 成本混入 |
| QD-CO-10 | 破玻璃全平台成本含 org_id=0 | P-SUPER-PLAT | 全平台视图看成本 | 为 rollup（kb_id IS NULL）口径，含未归属评测成本 |
| QD-CO-11 | 公开库成本计入本组织 | P-OWNER | KB-PUB 有成本 | 计入组织 A 成本；多组织可同时计入公开库 |
| QD-CO-12 | 成本精度两位小数 | P-OWNER | 看所有金额 | 一律两位小数，四舍五入（HALF_UP），无科学计数法 |
| QD-CO-13 | 成本 days 独立参数 | P-OWNER | /cost?days=30 vs 默认 | 仅影响成本，互不串区间 |
| QD-CO-14 | 来源标签映射正确 | P-OWNER | 看图例 | PRODUCTION→线上生产、GOLDEN_SET→黄金集、MANUAL→手动评测，无错配 |

## QD-ORG 组织视角隔离 / 切换 / 破玻璃

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| QD-ORG-01 | 普通成员可进质量看板（下放后） | P-MEMBER | 访问 /evaluation/quality | 可进入（非 403），看本组织数据 |
| QD-ORG-02 | 个人组织 owner 看个人口径 | P-PERSONAL | 进质量看板 | 仅本人个人组织 + 公开库数据 |
| QD-ORG-03 | 三态切换数据正确 | P-SUPER | 组织A→B→个人 切换 | 每次切换 KPI/by-kb/cost 全量刷新为对应口径 |
| QD-ORG-04 | 进入全平台视图看聚合 | P-SUPER-PLAT | 切「全平台视图」 | 数据为全平台聚合 |
| QD-ORG-05 | 退出全平台视图回组织口径 | P-SUPER | 从全平台切回组织 A | 立即回到组织 A 口径，不残留聚合数 |
| QD-ORG-06 | 越权-他组织 kbId | P-MEMBER | 构造 ?kbId=KB-B1 | 403，不泄漏 |
| QD-ORG-07 | 越权-他组织 judgeResultId | P-MEMBER | /case/{B的id} | 403，不泄漏 |
| QD-ORG-08 | 无组织上下文不泄漏 | 构造无 X-Org-Id 且非破玻璃 | 调各接口 | 返回空集（无数据），绝不返回全平台 |
| QD-ORG-09 | 公开库被多组织同时计入 | P-OWNER & P-ORGB | A、B 分别看含 KB-PUB 的口径 | 两组织都能看到 KB-PUB 数据，互不影响私有部分 |
| QD-ORG-10 | 切组织后页内多区块联动一致 | P-SUPER | 切组织后比对 | 概览/by-kb/worst-cases/cost 同属一个组织口径，无某区块未刷新 |
| QD-ORG-11 | 破玻璃聚合≥各组织 | P-SUPER-PLAT | 全平台 vs A vs B | 全平台样本/成本 ≥ A、≥ B |
| QD-ORG-12 | 切组织时旧请求竞态不串数据 | P-SUPER | 快速连续切 A→B | 最终展示 B 数据，无 A 的迟到响应覆盖（竞态/防抖） |

---

# 模块二：模型 & 成本（/models）

三子 tab：**模型管理** / **成本看板** / **用户配额**。
接口：`/models`（列表，含每模型 monthlyCost）、`/models/cost/stats`（KPI+趋势+排行）、`/models/cost/detail`（按用途×模型）、`/models/{code}/toggle`（破玻璃+ADMIN）。
口径：**模型列表/数量=全局**；**成本（费用/Token/调用/明细/趋势）=按当前组织 org_id**；破玻璃=全平台聚合（含 org_id=0）。

## MC-ML 模型管理列表与接入 KPI

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| MC-ML-01 | 列出全部已接入模型 | P-OWNER | 看模型管理 tab | 列出全部模型（含本地 jina），行数=接入模型 KPI |
| MC-ML-02 | 接入模型 KPI=总数/启用数 | P-OWNER | 看 modelCount/enabledCount | 与列表实际启用/停用统计一致 |
| MC-ML-03 | 模型列表跨组织一致（全局） | P-OWNER vs P-ORGB | 两组织看列表 | 模型集合、定价、启停状态完全相同 |
| MC-ML-04 | 每模型 monthlyCost 随组织变 | P-SUPER | A、B 切换看某模型 monthlyCost | 同一模型在 A、B 下 monthlyCost 不同（按 org） |
| MC-ML-05 | vendor/purpose/定价正确显示 | P-OWNER | 看各列 | vendor、用途标签、input/output 定价与配置一致 |
| MC-ML-06 | 本地模型标记 | P-OWNER | 看 jina-reranker | isLocal=true，显示「本地/自托管」，定价为 0 / 无 API 费 |
| MC-ML-07 | 已停用模型徽章 | P-OWNER | 看停用模型 | 状态「已停用」，开关呈关闭态 |
| MC-ML-08 | 主用模型标记 | P-OWNER | 看 isPrimary | 主用模型有标识，与 fallback 区分 |
| MC-ML-09 | 切组织列表不变但成本变 | P-SUPER | A→B | 列表行不变；monthlyCost 列变化 |
| MC-ML-10 | 空用量组织每模型成本 0 | P-MEMBER(组织C) | 看列表 monthlyCost | 全部 ¥0.00，无 NaN |
| MC-ML-11 | 定价精度与单位 | P-OWNER | 看定价列 | 金额格式正确（每千/百万 token 口径一致），无错位 |
| MC-ML-12 | 模型数量 KPI 与行数一致 | P-OWNER | 对比 | modelCount == 列表渲染行数 |

## MC-TG 模型开关权限（绑定全平台视图 / 破玻璃）

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| MC-TG-01 | 组织 OWNER 开关只读 | P-OWNER | 看开关 | disabled，不可点 |
| MC-TG-02 | 组织 MEMBER 开关只读 | P-MEMBER | 看开关 | disabled |
| MC-TG-03 | 个人组织 owner 开关只读 | P-PERSONAL | 看开关 | disabled |
| MC-TG-04 | 超管未破玻璃开关只读 | P-SUPER | 组织上下文看开关 | disabled（canManageModels=isAdmin && isPlatform） |
| MC-TG-05 | 超管全平台视图可操作 | P-SUPER-PLAT | 全平台视图看开关 | 开关可点，可启停 |
| MC-TG-06 | 组织用户点击开关无效 | P-OWNER | 强制点击 disabled 开关 | onToggle 直接 return，无请求发出 |
| MC-TG-07 | 后端-组织用户直调 toggle | P-MEMBER | 直接 PUT /models/{code}/toggle | 403 MODEL_TOGGLE_REQUIRES_PLATFORM_VIEW |
| MC-TG-08 | 后端-超管非破玻璃直调 | P-SUPER | PUT toggle（未破玻璃） | 403（破玻璃未生效） |
| MC-TG-09 | 停用后用途仍可解析才允许 | P-SUPER-PLAT | 停用某用途的备用模型 | 主用仍在→允许；成功 |
| MC-TG-10 | 停用致用途无可用→409 | P-SUPER-PLAT | 停用某用途最后一个可用模型 | 409 MODEL_DISABLE_WOULD_LEAVE_PURPOSE_UNAVAILABLE |
| MC-TG-11 | 开关状态即时生效与回滚 | P-SUPER-PLAT | 切换开关；制造失败 | 成功即时反映；失败回滚到原状态 |
| MC-TG-12 | 切回组织上下文恢复只读 | P-SUPER → 切回组织 A | 从全平台切回组织 | 开关重新变 disabled |

## MC-CK 成本 KPI 统计（费用 / Token / 调用 / 成功率）

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| MC-CK-01 | 本月费用=本组织本月成本之和 | P-OWNER | 看成本看板 monthlyCost | 等于组织 A 本月 model_usage 成本之和 |
| MC-CK-02 | 本月 Token=输入+输出 | P-OWNER | 看 Token KPI | == monthlyInputTokens + monthlyOutputTokens |
| MC-CK-03 | 调用次数正确 | P-OWNER | 看 callCount | == 本组织本月 call 之和 |
| MC-CK-04 | 副描述输入/输出与 KPI 一致 | P-OWNER | 看「输入 X · 输出 Y」 | X==monthlyInputTokens、Y==monthlyOutputTokens 格式化一致 |
| MC-CK-05 | 成功率计算正确 | P-OWNER | 看「成功率 X%」 | == success/total，0 分母不报错 |
| MC-CK-06 | 空用量组织全 0 | P-MEMBER(组织C) | 看 KPI | 费用 ¥0.00、Token 0、调用 0、成功率合理（0% 或 —），无 NaN |
| MC-CK-07 | 切组织 KPI 变化 | P-SUPER | A→B | 三 KPI 全部随组织变化 |
| MC-CK-08 | 组织 A KPI 不含 B | P-OWNER | 比对已知值 | 严格等于 A |
| MC-CK-09 | 破玻璃全平台聚合含 org_id=0 | P-SUPER-PLAT | 全平台视图看 KPI | 含未归属（评测 org_id=0）成本，≥ 各组织 |
| MC-CK-10 | 本月费用==明细各行之和 | P-OWNER | KPI vs Σ detail.cost | 完全相等（求和一致性） |
| MC-CK-11 | 大数 Token 格式化 | P-OWNER | 看 1.2M / 24.6K 等 | 格式化正确，不丢精度量级 |
| MC-CK-12 | 成功率边界 0%/100% | 构造全成功/全失败用量 | 看成功率 | 显示 100.0% / 0.0%，无越界 |
| MC-CK-13 | 接入模型数全局 vs 成本按组织 | P-SUPER | A→B 对比 | modelCount 不变、成本 KPI 变（口径分离正确） |

## MC-CD 成本明细 / 趋势 / 按用途分摊

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| MC-CD-01 | 明细按用途×模型聚合 | P-OWNER | 看 detail | 每行 purpose+modelCode 唯一，聚合正确 |
| MC-CD-02 | 明细按成本降序 | P-OWNER | 看排序 | cost 降序 |
| MC-CD-03 | 明细各行之和==本月费用 | P-OWNER | Σ detail.cost vs KPI | 相等 |
| MC-CD-04 | 趋势按天 byPurpose 堆叠 | P-OWNER | 看 trend | 每天一点，byPurpose 各用途分量正确 |
| MC-CD-05 | 趋势 days 参数 | P-OWNER | days=7/30 | 趋势点数对应天数 |
| MC-CD-06 | 排行 pct 之和≈100% | P-OWNER | 看 ranking | Σ pct ≈ 100%（容差内） |
| MC-CD-07 | 明细 token/calls 正确 | P-OWNER | 看各行 | inputTokens/outputTokens/callCount 与灌入一致 |
| MC-CD-08 | 平均延迟=总延迟/调用 | P-OWNER | 看 avgLatencyMs | == totalLatency/calls，calls=0 时为 0 不除零 |
| MC-CD-09 | 空组织明细空/趋势全 0 | P-MEMBER(组织C) | 看 detail/trend | 明细空列表、趋势全 0，无 NaN |
| MC-CD-10 | 切组织明细变化 | P-SUPER | A→B | 明细行与数值随组织变 |
| MC-CD-11 | 明细不含他组织用量 | P-OWNER | 检查 | 无 B 的用量行 |
| MC-CD-12 | 破玻璃明细含 org_id=0 | P-SUPER-PLAT | 全平台看明细 | 含评测/未归属用量（如评测 Judge 行） |

## MC-ORG 组织视角成本隔离 / 切换 / 破玻璃

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| MC-ORG-01 | 普通成员可进模型&成本（下放后） | P-MEMBER | 访问 /models | 可进入，看本组织成本 + 只读模型 |
| MC-ORG-02 | 成员看本组织成本口径 | P-MEMBER | 看 KPI/明细 | 仅本组织（+评测归属规则），无他组织 |
| MC-ORG-03 | 三态切换成本正确 | P-SUPER | A→B→个人 | 每次成本全量刷新为对应组织 |
| MC-ORG-04 | 成本不含他组织 | P-OWNER | 比对已知值 | 严格等于 A |
| MC-ORG-05 | 破玻璃全平台聚合 | P-SUPER-PLAT | 全平台看成本 | 聚合 ≥ 各组织，含 org_id=0 |
| MC-ORG-06 | 评测成本仅全平台可见 | P-OWNER vs P-SUPER-PLAT | 对比组织视图与全平台 | 组织视图不含 org_id=0 评测成本；全平台含 |
| MC-ORG-07 | 退破玻璃回组织口径 | P-SUPER | 切回组织 A | 成本回组织 A，不残留聚合 |
| MC-ORG-08 | 无组织上下文成本为空 | 构造无 X-Org-Id 且非破玻璃 | 调 cost/stats | 返回空/0（org_id=-1 兜底），不泄漏全平台 |
| MC-ORG-09 | 个人组织成本独立 | P-PERSONAL | 看成本 | 仅个人组织 org_id 的用量 |
| MC-ORG-10 | 切组织后 KPI+明细+趋势联动 | P-SUPER | 切组织后比对三区块 | 同属一个组织口径，无区块未刷新 |
| MC-ORG-11 | 模型列表（全局）不随组织变-对照 | P-SUPER | 切组织对照列表 vs 成本 | 列表稳定、成本变化，口径分离无误 |
| MC-ORG-12 | 切组织竞态不串数据 | P-SUPER | 快速连切 A→B | 最终显示 B，无 A 迟到响应覆盖 |

## MC-UI 子 tab / 提示条 / 用户配额占位

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| MC-UI-01 | 三子 tab 齐全 | P-OWNER | 看 tab | 模型管理 / 成本看板 / 用户配额 三个 |
| MC-UI-02 | 默认进入的 tab | P-OWNER | 进页面 | 默认落在约定 tab（如模型管理），状态正确 |
| MC-UI-03 | 用户配额「待开发」占位 | P-OWNER | 进用户配额 tab | 显示待开发占位，无报错、无空白崩溃 |
| MC-UI-04 | 提示条-组织视图文案 | P-OWNER | 看顶部提示条 | 含「成本按当前组织(组织名)统计」「模型启停仅平台管理员可操作」 |
| MC-UI-05 | 提示条-全平台视图文案 | P-SUPER-PLAT | 看提示条 | 含「全平台视图（破玻璃）」「聚合（含未归属评测成本）」 |
| MC-UI-06 | 提示条随组织切换更新 | P-SUPER | 切组织 | 提示条组织名/口径随之变化 |
| MC-UI-07 | tab 切换不丢组织上下文 | P-OWNER | 模型管理↔成本看板 | 切 tab 后仍是同一组织口径 |
| MC-UI-08 | 成本看板 tab 结构完整 | P-OWNER | 看成本看板 | KPI + 趋势 + 排行/明细 均渲染 |
| MC-UI-09 | 模型管理 tab 结构完整 | P-OWNER | 看模型管理 | 接入 KPI + 模型列表 均渲染 |
| MC-UI-10 | 切组织后保持当前 tab | P-SUPER | 在成本看板切组织 | 仍停留成本看板，数据更新 |
| MC-UI-11 | 用户配额 tab 无多余请求 | P-OWNER | 监听网络 | 纯占位，不触发数据接口 |
| MC-UI-12 | 窄屏/移动端响应式 | P-OWNER | 缩窄视口 | tab 与表格不错位、可横向滚动（可选项） |

---

## 附：后续模块占位（本期不展开）

| 模块 | 前缀 | 状态 |
|---|---|---|
| 驾驶舱 | DB | 待补 |
| 知识库管理 | KB | 待补 |
| 检索调试台 | SE | 待补 |
| 应答调试台 | AN | 待补 |
| 评测实验室 | EV | 待补 |
| 开发者中心 | DV | 待补 |

> 维护约定：新增模块沿用「角色 / 数据前置 / 断言基线（求和一致性·组织隔离·公开库·破玻璃·空态·边界·切换一致）」七项通用维度，保证全平台用例口径统一。
