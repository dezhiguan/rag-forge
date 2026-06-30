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

# 模块三：驾驶舱（/）

接口：`GET /metrics/dashboard` → 资产 `DashboardMetricsVO`(kbCount/documentCount/chunkCount)、最近动态 `DashboardActivityVO`、趋势 `DashboardTrendPointVO`。
口径：**资产按本组织自有库统计**（"别人的公开库不计入你的资产"）；动态/趋势按本组织；破玻璃=全平台。

## DB-AS 资产统计正确性

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| DB-AS-01 | 知识库数=本组织自有库数 | P-OWNER | 看 kbCount | 等于组织 A 自有 KB 数（不含他组织、**不含别人的公开库**） |
| DB-AS-02 | 文档数=本组织各库文档之和 | P-OWNER | 看 documentCount | == 组织 A 各库文档求和 |
| DB-AS-03 | 切片数=本组织各库切片之和 | P-OWNER | 看 chunkCount | == 组织 A 各库切片求和 |
| DB-AS-04 | 公开库（他组织创建）不计入资产 | P-OWNER | 存在他组织公开库时看资产 | 资产数不含他组织公开库的文档/切片 |
| DB-AS-05 | 本组织自建的公开库计入资产 | P-OWNER | A 自建 KB-PUB | KB-PUB 计入 A 的资产 |
| DB-AS-06 | 空组织资产全 0 | P-MEMBER(组织C) | 看资产 | kb/doc/chunk 全 0，无 NaN |
| DB-AS-07 | 切组织资产变化 | P-SUPER | A→B | 三项资产随组织变 |
| DB-AS-08 | 组织 A 资产不含 B | P-OWNER | 比对已知值 | 严格等于 A |
| DB-AS-09 | 破玻璃资产为全平台 | P-SUPER-PLAT | 全平台视图 | 资产 ≥ 各组织，约等于全平台总量 |
| DB-AS-10 | 删除库后资产实时减少 | P-OWNER | 删一个库再看 | 资产相应减少，无脏缓存 |
| DB-AS-11 | 入库中文档的计数口径 | P-OWNER | 有处理中文档 | 文档/切片计数口径明确（已完成 vs 全部）一致可解释 |
| DB-AS-12 | 资产为非负整数 | P-OWNER | 看数值 | 均为非负整数，无负数/小数 |

## DB-AC 最近动态与失败重试权限

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| DB-AC-01 | 动态仅含本组织 | P-OWNER | 看最近动态列表 | 只含组织 A 的文档/操作，无 B |
| DB-AC-02 | 动态按时间倒序 | P-OWNER | 看排序 | 最新在前 |
| DB-AC-03 | 失败文档可重试（本组织 admin） | P-OWNER | 看失败项 | 显示可重试入口，retryable=true |
| DB-AC-04 | 平台视图失败动态只读不可重试 | P-SUPER-PLAT | 全平台看失败项 | 提示"需该组织管理员处理"，retryable=false |
| DB-AC-05 | 普通成员对失败项的权限 | P-MEMBER | 看失败项 | 按角色：无写权限则不显示重试/点击无效 |
| DB-AC-06 | 越权重试他组织文档 | P-OWNER | 构造重试 B 的 docId | 403/不可见 |
| DB-AC-07 | 空组织动态空状态 | P-MEMBER(组织C) | 看动态 | 空列表 + 友好空状态 |
| DB-AC-08 | 动态文案与状态映射 | P-OWNER | 看各状态 | 解析中/成功/失败 文案与状态正确映射 |
| DB-AC-09 | 切组织动态刷新 | P-SUPER | A→B | 动态列表整体刷新为 B |
| DB-AC-10 | 动态条目跳转正确 | P-OWNER | 点动态项 | 跳到对应文档详情，id 一致且属本组织 |
| DB-AC-11 | 动态数量上限 | P-OWNER | 大量操作后看 | 限制条数（如最近 N 条），不无限增长 |
| DB-AC-12 | 破玻璃动态含多组织 | P-SUPER-PLAT | 全平台看 | 含跨组织动态，标注归属可辨 |

## DB-TR 趋势图

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| DB-TR-01 | 趋势按本组织统计 | P-OWNER | 看趋势 | 数据点仅本组织口径 |
| DB-TR-02 | 趋势点与时间轴对应 | P-OWNER | 看 x 轴 | 每日/每周一点，无缺漏/重复 |
| DB-TR-03 | 趋势求和与资产/动态一致 | P-OWNER | 交叉核对 | 趋势累计与对应总量可解释一致 |
| DB-TR-04 | 空组织趋势全 0 | P-MEMBER(组织C) | 看趋势 | 全 0 平线，不报错 |
| DB-TR-05 | 切组织趋势刷新 | P-SUPER | A→B | 趋势整体刷新 |
| DB-TR-06 | 组织 A 趋势不含 B | P-OWNER | 比对 | 严格本组织 |
| DB-TR-07 | 破玻璃趋势为全平台 | P-SUPER-PLAT | 全平台 | 聚合趋势 ≥ 各组织 |
| DB-TR-08 | 趋势 count 非负整数 | P-OWNER | 看 count | 非负整数 |
| DB-TR-09 | 时间范围切换趋势变化 | P-OWNER | 切范围 | 点数/数值随范围变 |
| DB-TR-10 | 跨月/跨年边界归集 | P-OWNER | 跨边界区间 | 正确落点，无错月 |
| DB-TR-11 | 趋势 tooltip 数值正确 | P-OWNER | hover 点 | tooltip 数值与数据点一致 |
| DB-TR-12 | 趋势与时区一致 | P-OWNER | 当天数据 | 按统一时区归日，不偏移 |

## DB-ORG 组织视角隔离 / 切换 / 破玻璃

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| DB-ORG-01 | 普通成员可进驾驶舱 | P-MEMBER | 访问 / | 可进入，看本组织 |
| DB-ORG-02 | 个人组织 owner 看个人口径 | P-PERSONAL | 进驾驶舱 | 仅个人组织资产 |
| DB-ORG-03 | 三态切换正确 | P-SUPER | A→B→个人 | 资产/动态/趋势全量刷新 |
| DB-ORG-04 | 进全平台视图看聚合 | P-SUPER-PLAT | 切全平台 | 全平台聚合 |
| DB-ORG-05 | 退全平台回组织口径 | P-SUPER | 切回 A | 立即回 A，无残留 |
| DB-ORG-06 | 无组织上下文不泄漏 | 构造无 X-Org-Id 非破玻璃 | 调 dashboard | 返回空/0，不泄漏全平台 |
| DB-ORG-07 | 资产口径"公开库不计入"专项 | P-OWNER & P-ORGB | A 看含 B 公开库时 | A 资产不含 B 的公开库（区别于质量/成本"公开库纳入"，资产是自有口径） |
| DB-ORG-08 | 三区块联动一致 | P-SUPER | 切组织后 | 资产/动态/趋势同属一个组织 |
| DB-ORG-09 | 破玻璃聚合≥各组织 | P-SUPER-PLAT | 对比 | 全平台 ≥ A、≥ B |
| DB-ORG-10 | 切组织竞态不串数据 | P-SUPER | 连切 A→B | 最终为 B，无 A 迟到覆盖 |
| DB-ORG-11 | 资产 vs 质量/成本口径差异专项 | P-OWNER | 对照三处对公开库处理 | 驾驶舱资产=自有口径；质量/成本=本组织∪公开（口径差异符合设计，非 bug） |
| DB-ORG-12 | 刷新保持当前组织 | P-OWNER | 刷新页面 | 仍为当前组织上下文（localStorage） |

---

# 模块四：知识库管理（/knowledge）

接口：列表（org ∪ 公开，分页 10/页）、创建/编辑/删除、可见性（PRIVATE/PUBLIC）、文档列表。
口径：列出本组织库 + 公开库；新建库绑定当前组织；写操作按 KB 访问权限。

## KB-LS 列表与分页

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| KB-LS-01 | 列出本组织库 + 公开库 | P-OWNER | 看列表 | 含 A 自有库 + 公开库；不含 B 私有库 |
| KB-LS-02 | 默认每页 10 条 | P-OWNER | 库 >10 时看分页 | 首页 10 条 |
| KB-LS-03 | 翻页正确 | P-OWNER | 点下一页 | 第 2 页内容正确，无重复/遗漏 |
| KB-LS-04 | 末页与边界 | P-OWNER | 翻到末页 | 末页条数正确，禁用"下一页" |
| KB-LS-05 | 重新加载回第 1 页 | P-OWNER | 刷新/重载 | kbPage 重置为 1 |
| KB-LS-06 | 删除后分页夹紧 | P-OWNER | 末页删到空 | 自动回退到有效页（watch 夹紧） |
| KB-LS-07 | 他组织私有库不可见 | P-MEMBER | 检查列表 | 无 KB-B1 |
| KB-LS-08 | 公开库标识 | P-OWNER | 看公开库 | 有"公开"标识，与私有区分 |
| KB-LS-09 | 空组织列表空态 | P-MEMBER(组织C) | 看列表 | 空状态（仅公开库，若有） |
| KB-LS-10 | 切组织列表刷新且回第 1 页 | P-SUPER | A→B | 列表换为 B 口径，页码重置 |
| KB-LS-11 | 列表每项统计（文档/切片）正确 | P-OWNER | 看每项计数 | 与该库实际一致 |
| KB-LS-12 | 破玻璃列出全平台库 | P-SUPER-PLAT | 全平台 | 列出全平台库 |

## KB-CRUD 创建 / 编辑 / 删除

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| KB-CRUD-01 | 新建库绑定当前组织 | P-OWNER | 在组织 A 建库 | 库 org_id=A，出现在 A 列表 |
| KB-CRUD-02 | 个人组织建库归属个人 | P-PERSONAL | 建库 | 归属个人组织 |
| KB-CRUD-03 | 编辑库名/描述 | P-OWNER | 改名保存 | 持久化，列表刷新 |
| KB-CRUD-04 | 删除库级联清理 | P-OWNER | 删库 | 库及其文档/切片清理，资产减少 |
| KB-CRUD-05 | 越权编辑他组织库 | P-MEMBER | 构造改 B 的库 | 403 |
| KB-CRUD-06 | 越权删除他组织库 | P-MEMBER | 构造删 B 的库 | 403 |
| KB-CRUD-07 | 无写权限成员建库 | P-MEMBER(仅读) | 尝试建库 | 按角色拒绝或不显示入口 |
| KB-CRUD-08 | 重名库处理 | P-OWNER | 建同名库 | 按规则（允许/拒绝）一致，无脏数据 |
| KB-CRUD-09 | 必填校验 | P-OWNER | 空名提交 | 前后端校验拦截 |
| KB-CRUD-10 | 删除二次确认 | P-OWNER | 点删除 | 有确认弹窗，取消不删 |
| KB-CRUD-11 | 创建后立即可用于检索 | P-OWNER | 建库后去检索台 | 新库出现在可选范围 |
| KB-CRUD-12 | 切组织后建库归属正确 | P-SUPER | 切到 B 建库 | 归属 B，不串到 A |

## KB-VIS 可见性与公开库

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| KB-VIS-01 | 设为公开后他组织可见 | P-OWNER→P-ORGB | A 设 KB-PUB 公开 | B 的列表/检索可见 KB-PUB |
| KB-VIS-02 | 设为私有后他组织不可见 | P-OWNER→P-ORGB | A 改回私有 | B 不再可见 |
| KB-VIS-03 | 公开库仅创建组织可编辑 | P-ORGB | B 尝试编辑 A 的公开库 | 403（公开=可读不可写） |
| KB-VIS-04 | 公开库被他组织检索 | P-ORGB | B 检索 KB-PUB | 可检索到内容 |
| KB-VIS-05 | 公开库计入质量/成本但不计资产 | P-OWNER | 交叉核对 | 符合口径差异（DB-ORG-11） |
| KB-VIS-06 | 可见性切换即时生效 | P-OWNER | 切换可见性 | 他组织视角即时变化 |
| KB-VIS-07 | 默认可见性 | P-OWNER | 新建库 | 默认 PRIVATE（按设计） |
| KB-VIS-08 | 公开库删除影响 | P-OWNER | 删除被引用公开库 | 引用方优雅降级，不 500 |
| KB-VIS-09 | 可见性图标/文案 | P-OWNER | 看标识 | PUBLIC/PRIVATE 标识正确 |
| KB-VIS-10 | 越权改可见性 | P-MEMBER | 构造改 B 库可见性 | 403 |
| KB-VIS-11 | 公开库列表去重 | P-OWNER | A 自有公开库 | 不重复出现（自有 + 公开两条件去重） |
| KB-VIS-12 | 破玻璃全可见 | P-SUPER-PLAT | 全平台 | 私有/公开全可见 |

## KB-ORG 访问控制与组织隔离

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| KB-ORG-01 | 普通成员可进知识库 | P-MEMBER | 访问 /knowledge | 可进，看本组织 + 公开 |
| KB-ORG-02 | 直接访问他组织库详情 | P-MEMBER | 构造 /knowledge/{B库}/documents | 403 |
| KB-ORG-03 | 文档列表按库隔离 | P-OWNER | 看某库文档 | 仅该库文档 |
| KB-ORG-04 | 越权访问他组织文档 | P-MEMBER | 构造他组织 docId | 403 |
| KB-ORG-05 | 三态切换列表正确 | P-SUPER | A→B→个人 | 列表口径正确切换 |
| KB-ORG-06 | 上传文档绑定库与组织 | P-OWNER | 上传到 A 的库 | 文档归属正确 |
| KB-ORG-07 | 越权上传到他组织库 | P-MEMBER | 构造上传到 B 库 | 403 |
| KB-ORG-08 | 无组织上下文列表 | 无 X-Org-Id 非破玻璃 | 看列表 | 仅公开库或空，不泄漏私有 |
| KB-ORG-09 | KB 成员/权限维度（若有） | P-OWNER | 配置库成员 | 权限按设定生效 |
| KB-ORG-10 | 破玻璃跨组织管理 | P-SUPER-PLAT | 全平台 | 可见全平台库（读），写需谨慎口径 |
| KB-ORG-11 | 切组织竞态 | P-SUPER | 连切 | 无迟到响应串库 |
| KB-ORG-12 | 刷新保持组织 | P-OWNER | 刷新 | 组织上下文保持 |

---

# 模块五：检索调试台（/debug）

接口：`POST /search`、`POST /search/by-image`。范围受 `KbAccessGuard`；检索日志按组织/KB 归属。

## SE-RUN 检索执行与策略

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| SE-RUN-01 | hybrid 策略返回结果 | P-OWNER | 选 hybrid 检索 | 返回融合排序结果 |
| SE-RUN-02 | 纯向量策略 | P-OWNER | vector 策略 | 仅向量召回 |
| SE-RUN-03 | 纯 BM25 策略 | P-OWNER | bm25 策略 | 仅关键词召回 |
| SE-RUN-04 | 结果含 rerank 分 | P-OWNER | 看结果项 | 有 rerank score，排序与分数一致 |
| SE-RUN-05 | 空查询校验 | P-OWNER | 提交空 query | 前后端拦截，不发无效请求 |
| SE-RUN-06 | 超长 query | P-OWNER | 超长输入 | 截断/正常处理，不 500 |
| SE-RUN-07 | 无结果命中 | P-OWNER | 查冷门词 | 返回空结果 + 空态 UI |
| SE-RUN-08 | 结果 chunk 内容/来源正确 | P-OWNER | 看命中片段 | 片段属所选库，来源标注正确 |
| SE-RUN-09 | 图片检索 by-image | P-OWNER | 上传图检索 | 返回多模态结果（若启用） |
| SE-RUN-10 | 检索耗时展示 | P-OWNER | 看耗时 | 展示召回/精排耗时，合理非负 |
| SE-RUN-11 | 特殊字符/注入 query | P-OWNER | 含特殊符号 | 安全处理，无注入/报错 |
| SE-RUN-12 | 重复检索结果稳定 | P-OWNER | 同 query 多次 | 结果稳定可复现 |

## SE-KB KB 选择与范围隔离

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| SE-KB-01 | 可选库=本组织 + 公开 | P-OWNER | 看 KB 下拉 | 含 A 库 + 公开库，不含 B 私有 |
| SE-KB-02 | 选本组织库检索 | P-OWNER | 选 KB-A1 | 仅在该库检索 |
| SE-KB-03 | 选公开库检索 | P-OWNER | 选 KB-PUB | 正常检索公开库 |
| SE-KB-04 | 越权检索他组织库 | P-MEMBER | 构造 kbIds=[B库] | 403 KB_ACCESS_DENIED |
| SE-KB-05 | 多库联合检索 | P-OWNER | 选多个本组织库 | 跨库融合结果 |
| SE-KB-06 | 混入他组织库的请求 | P-MEMBER | kbIds=[A库,B库] | 整体 403 或仅返回有权库（按设计），绝不泄漏 B |
| SE-KB-07 | 不选库默认范围 | P-OWNER | 不选库直接搜 | 按默认（全本组织可读库）或提示选择 |
| SE-KB-08 | 空组织无可选库 | P-MEMBER(组织C) | 看下拉 | 空/仅公开库 |
| SE-KB-09 | 切组织可选库刷新 | P-SUPER | A→B | 下拉换为 B 库 |
| SE-KB-10 | 检索日志归属本组织 | P-OWNER | 检索后查日志 | retrieval_log org/KB 归属正确 |
| SE-KB-11 | 删除库后不可再选 | P-OWNER | 删库后看下拉 | 该库消失 |
| SE-KB-12 | 破玻璃可选全平台库 | P-SUPER-PLAT | 全平台 | 可选全平台库 |

## SE-PARAM 参数与结果正确性

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| SE-PARAM-01 | topK 生效 | P-OWNER | 设 topK=5 | 最多返回 5 条 |
| SE-PARAM-02 | topK 边界=1 | P-OWNER | topK=1 | 返回 1 条 |
| SE-PARAM-03 | topK 超大 | P-OWNER | topK=1000 | 返回可用上限，不报错 |
| SE-PARAM-04 | 向量权重调节 | P-OWNER | 调 vectorWeight | 排序随权重变化（可观测） |
| SE-PARAM-05 | 权重边界 0/1 | P-OWNER | weight=0、1 | 退化为纯 BM25 / 纯向量 |
| SE-PARAM-06 | 非法参数回退 | P-OWNER | topK=0/负 | 钳制/默认，不报错 |
| SE-PARAM-07 | 分数归一化区间 | P-OWNER | 看分数 | 在合理区间，无越界 |
| SE-PARAM-08 | 结果排序与分数一致 | P-OWNER | 核对 | 降序，无错位 |
| SE-PARAM-09 | RRF 融合正确性 | P-OWNER | hybrid 对照单路 | 融合结果合理，含两路贡献 |
| SE-PARAM-10 | 参数持久/重置 | P-OWNER | 调参后重置 | 重置回默认 |
| SE-PARAM-11 | 结果高亮/片段定位 | P-OWNER | 看命中 | 命中词/片段定位正确 |
| SE-PARAM-12 | 并发检索互不串 | P-OWNER | 快速多次不同 query | 结果对应各自 query，无错配 |

---

# 模块六：应答调试台（/answer）

接口：`POST /answer`（RAG 应答）。范围受 KbAccessGuard；应答成本/用量按组织归属。

## AN-RUN 应答生成

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| AN-RUN-01 | 正常生成应答 | P-OWNER | 提问 | 返回答案文本 |
| AN-RUN-02 | 答案带引用来源 | P-OWNER | 看引用 | 列出来源 chunk，属所选库 |
| AN-RUN-03 | 空问题校验 | P-OWNER | 空输入 | 拦截 |
| AN-RUN-04 | 无相关上下文 | P-OWNER | 问库外问题 | 答"无依据/不知道"，不幻觉编造 |
| AN-RUN-05 | 流式/分段输出 | P-OWNER | 看输出 | 流式正常，无截断错乱 |
| AN-RUN-06 | 超长问题 | P-OWNER | 超长输入 | 正常处理或截断 |
| AN-RUN-07 | 引用可点击溯源 | P-OWNER | 点引用 | 跳到对应片段，属本组织库 |
| AN-RUN-08 | 答案与检索结果一致 | P-OWNER | 对照 | 答案基于检索片段，无凭空来源 |
| AN-RUN-09 | 重复提问稳定性 | P-OWNER | 同问多次 | 答案大致稳定，无崩溃 |
| AN-RUN-10 | 特殊字符/注入 | P-OWNER | 注入式提问 | 安全处理 |
| AN-RUN-11 | 多轮上下文（若支持） | P-OWNER | 连续追问 | 上下文连贯或按设计无状态 |
| AN-RUN-12 | 生成失败优雅降级 | P-OWNER | 制造 LLM 失败 | 友好报错，不白屏 |

## AN-KB KB 范围与隔离

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| AN-KB-01 | 可选库=本组织 + 公开 | P-OWNER | 看下拉 | 含 A + 公开，不含 B 私有 |
| AN-KB-02 | 选本组织库应答 | P-OWNER | 选 KB-A1 | 基于该库应答 |
| AN-KB-03 | 越权选他组织库 | P-MEMBER | kbIds=[B库] | 403 |
| AN-KB-04 | 公开库应答 | P-OWNER | 选 KB-PUB | 正常应答 |
| AN-KB-05 | 混入他组织库 | P-MEMBER | [A库,B库] | 不泄漏 B，整体 403 或过滤 |
| AN-KB-06 | 空组织无可选库 | P-MEMBER(组织C) | 看下拉 | 空/仅公开 |
| AN-KB-07 | 切组织可选库刷新 | P-SUPER | A→B | 下拉换 B |
| AN-KB-08 | 答案引用不含他组织 | P-OWNER | 看引用 | 引用片段全属本组织/公开库 |
| AN-KB-09 | 应答日志归属本组织 | P-OWNER | 查 answer_log | org/KB 归属正确 |
| AN-KB-10 | 删除库后不可选 | P-OWNER | 删库 | 下拉消失 |
| AN-KB-11 | 破玻璃可选全平台 | P-SUPER-PLAT | 全平台 | 可选全平台库 |
| AN-KB-12 | 无组织上下文 | 无 X-Org-Id 非破玻璃 | 应答 | 仅公开或拒绝，不泄漏私有 |

## AN-COST 应答成本与用量归属

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| AN-COST-01 | 应答产生的用量计入本组织 | P-OWNER | 应答后看模型&成本 | 本组织成本/Token/调用相应增加 |
| AN-COST-02 | 他组织成本不受影响 | P-OWNER | 对照 B | B 成本不变 |
| AN-COST-03 | Token 统计含输入+输出 | P-OWNER | 核对 | 与应答实际 token 一致 |
| AN-COST-04 | 失败应答的计数口径 | P-OWNER | 制造失败 | failed 计数正确，不计成功 |
| AN-COST-05 | 用量按用途归类（ANSWER） | P-OWNER | 看成本明细 | 归到 ANSWER 用途 |
| AN-COST-06 | 改写产生的用量（REWRITE） | P-OWNER | 触发改写 | 归到 REWRITE 用途 |
| AN-COST-07 | 个人组织应答成本独立 | P-PERSONAL | 应答后看成本 | 计入个人组织 |
| AN-COST-08 | 破玻璃聚合含本次 | P-SUPER-PLAT | 全平台看 | 含本次应答用量 |
| AN-COST-09 | 成本归属与日志一致 | P-OWNER | 交叉核对 | answer_log 与 model_usage 口径一致 |
| AN-COST-10 | 高并发应答计量不丢 | P-OWNER | 并发应答 | 用量累计无丢失/无重复 |
| AN-COST-11 | 成本即时反映（或延迟可解释） | P-OWNER | 应答后刷新成本 | 按聚合周期反映，口径明确 |
| AN-COST-12 | 切组织后成本归属正确 | P-SUPER | 切 B 应答 | 计入 B 不计 A |

---

# 模块七：评测实验室（/eval）

接口：数据集 `/eval/datasets`、题目 `/eval/datasets/{id}/questions`、实验 `/eval/experiments`。
口径：**已逐条组织隔离**（资源所属 KB 必须在 本组织 ∪ 公开）；nav 仅组织 OWNER/ADMIN 可见。

## EV-DS 数据集管理与隔离

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| EV-DS-01 | 列表仅含本组织数据集 | P-OWNER | 看数据集列表 | 仅 A（含公开库的）数据集，无 B |
| EV-DS-02 | 创建数据集绑定本组织 KB | P-OWNER | 用 KB-A1 建 | 成功，归属 A |
| EV-DS-03 | 创建用他组织 KB 被拒 | P-OWNER | kbId=KB-B1 建 | 403 EVAL_RESOURCE_NOT_IN_ORG |
| EV-DS-04 | 创建用公开库 | P-OWNER | kbId=KB-PUB | 成功（公开库纳入范围） |
| EV-DS-05 | getById 他组织数据集越权 | P-OWNER | GET /eval/datasets/{B的id} | 403 |
| EV-DS-06 | 删除他组织数据集越权 | P-OWNER | 删 B 的数据集 | 403 |
| EV-DS-07 | 编辑本组织数据集 | P-OWNER | 改名 | 成功 |
| EV-DS-08 | 空组织数据集空列表 | P-OWNER(组织C) | 看列表 | 空 |
| EV-DS-09 | 切组织数据集刷新 | P-SUPER | A→B | 列表换 B |
| EV-DS-10 | 普通成员不可见评测入口 | P-MEMBER | 看侧栏 | 无"评测实验室"（orgRoles OWNER/ADMIN） |
| EV-DS-11 | 个人组织 owner 可用评测 | P-PERSONAL | 进评测 | 可见可用（个人组织 owner） |
| EV-DS-12 | 破玻璃可见全平台数据集 | P-SUPER-PLAT | 全平台 | 列出全平台 |

## EV-QS 题目管理

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| EV-QS-01 | 列题目校验数据集归属 | P-OWNER | 列本组织数据集题目 | 成功 |
| EV-QS-02 | 列他组织数据集题目越权 | P-OWNER | {B数据集}/questions | 403（经 requireDataset 隔离） |
| EV-QS-03 | 新增题目 | P-OWNER | 加题 | 成功，questionCount+1 |
| EV-QS-04 | 批量导入题目 | P-OWNER | batchCreate | 全部入库，计数正确 |
| EV-QS-05 | 他组织数据集加题越权 | P-OWNER | 给 B 数据集加题 | 403 |
| EV-QS-06 | 期望 chunk/片段字段 | P-OWNER | 填期望命中 | 正确保存 |
| EV-QS-07 | 空题目校验 | P-OWNER | 空提交 | 拦截 |
| EV-QS-08 | 删除题目计数 | P-OWNER | 删题 | questionCount-1 |
| EV-QS-09 | 题目数与列表一致 | P-OWNER | 核对 | count == 行数 |
| EV-QS-10 | 大批量题目分页/性能 | P-OWNER | 大量题 | 分页正常，不卡死 |
| EV-QS-11 | 切组织题目归属 | P-SUPER | 切 B | 题目随数据集隔离 |
| EV-QS-12 | 破玻璃跨组织题目 | P-SUPER-PLAT | 全平台 | 可见 |

## EV-EXP 实验运行与结果

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| EV-EXP-01 | 跑实验校验数据集归属 | P-OWNER | 跑本组织数据集 | 成功 |
| EV-EXP-02 | 跑他组织数据集越权 | P-OWNER | runExperiment(B数据集) | 403 |
| EV-EXP-03 | 实验列表仅本组织 | P-OWNER | 看 listRecent | 仅 A 的实验 |
| EV-EXP-04 | 实验详情 getDetail 越权 | P-OWNER | /experiments/{B的id} | 403 |
| EV-EXP-05 | 实验结果指标正确 | P-OWNER | 看结果 | 命中率/分数等与样本一致 |
| EV-EXP-06 | 实验对比（多策略） | P-OWNER | 对比两次实验 | 对比数据正确 |
| EV-EXP-07 | 实验产生成本计入本组织 | P-OWNER | 跑后看成本 | 本组织成本增加（或归 org_id=0 评测口径，按设计核对） |
| EV-EXP-08 | 空数据集跑实验 | P-OWNER | 无题数据集 | 友好提示，不崩 |
| EV-EXP-09 | 实验运行中状态 | P-OWNER | 看进度 | 状态流转正确 |
| EV-EXP-10 | 实验失败处理 | P-OWNER | 制造失败 | 优雅报错 |
| EV-EXP-11 | 切组织实验隔离 | P-SUPER | A→B | 实验列表/详情隔离 |
| EV-EXP-12 | 破玻璃全平台实验 | P-SUPER-PLAT | 全平台 | 可见全平台实验 |

---

# 模块八：开发者中心（/api）

三 tab：API 凭证 / 接口文档 / MCP 接入。
口径：key 绑定当前组织；接口文档/MCP 为静态全局（key 自动绑定组织，**无需 X-Org-Id**）；平台治理=破玻璃定向搜索 + 吊销。

## DV-KEY API 凭证生命周期

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| DV-KEY-01 | 列表仅含本组织 key | P-OWNER | 看 key 列表 | 仅 A 的 key，无 B |
| DV-KEY-02 | 创建 key 绑定当前组织 | P-OWNER | 建 key | key.org_id=A，掩码展示 |
| DV-KEY-03 | 平台视图列表为空（定向治理） | P-SUPER-PLAT | 全平台看列表 | 不浏览全部（Option B：返回空，靠 governance 搜索） |
| DV-KEY-04 | 改 key 名 | P-OWNER | PATCH 改名 | 成功 |
| DV-KEY-05 | 启用/停用 key | P-OWNER | toggle enable | 状态切换，停用后不可用 |
| DV-KEY-06 | 删除 key | P-OWNER | DELETE | 删除，列表移除 |
| DV-KEY-07 | 越权操作他组织 key | P-MEMBER | 改/删 B 的 key | 403 NOT_ORG_ADMIN/不可见 |
| DV-KEY-08 | 非组织管理员建 key | P-MEMBER | 尝试建 key | 按 org admin 规则拒绝 |
| DV-KEY-09 | key 掩码展示 | P-OWNER | 看 keyMasked | sk-xxxx****yyyy，不全明文 |
| DV-KEY-10 | 切组织 key 列表刷新 | P-SUPER | A→B | 列表换 B 的 key |
| DV-KEY-11 | 删除组织级联删 key | P-SUPER-PLAT | 删组织 A | A 的 key 一并删除 |
| DV-KEY-12 | lastUsedAt 更新 | P-OWNER | 用 key 调一次 API | last_used_at 更新 |

## DV-REVEAL 创建后明文展示

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| DV-REVEAL-01 | 创建后弹窗明文一次 | P-OWNER | 建 key | 弹窗显示完整明文 key |
| DV-REVEAL-02 | 关闭后不再可见 | P-OWNER | 关弹窗再看列表 | 仅掩码，明文不再出现 |
| DV-REVEAL-03 | 复制按钮可用 | P-OWNER | 点复制 | 复制完整明文 |
| DV-REVEAL-04 | 明文不入列表接口 | P-OWNER | 查列表响应 | 列表接口不返回明文 |
| DV-REVEAL-05 | 弹窗前缀/掩码一致 | P-OWNER | 对照 | 明文前后缀与列表掩码一致 |
| DV-REVEAL-06 | 刷新页面明文消失 | P-OWNER | 刷新 | 明文不可恢复 |
| DV-REVEAL-07 | 多次创建各自明文 | P-OWNER | 连建两把 | 各自明文正确，不串 |
| DV-REVEAL-08 | 弹窗安全提示 | P-OWNER | 看弹窗 | 有"仅此一次"提示 |
| DV-REVEAL-09 | 明文不写日志 | — | 查后端日志 | 不打印完整明文 |
| DV-REVEAL-10 | 取消创建不生成 | P-OWNER | 取消流程 | 不产生残留 key |
| DV-REVEAL-11 | 弹窗 ESC/遮罩关闭 | P-OWNER | 关弹窗 | 正常关闭 |
| DV-REVEAL-12 | 创建失败无明文 | P-OWNER | 制造失败 | 不显示明文，报错友好 |

## DV-GOV 平台治理（破玻璃）

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| DV-GOV-01 | 治理搜索需破玻璃 | P-SUPER（未破玻璃） | 调 governance | 拒绝/不可用 |
| DV-GOV-02 | 破玻璃按前缀搜索 | P-SUPER-PLAT | 搜 key 前缀 | 命中对应 key（跨组织） |
| DV-GOV-03 | 按名称搜索 | P-SUPER-PLAT | 搜 keyName | 命中 |
| DV-GOV-04 | 搜索词 <3 字符拒绝 | P-SUPER-PLAT | 搜 2 字符 | 拒绝（防全量拉取） |
| DV-GOV-05 | 结果上限 50 | P-SUPER-PLAT | 宽泛搜索 | 最多 50 条 |
| DV-GOV-06 | 吊销需理由 | P-SUPER-PLAT | 吊销不填理由 | 拒绝 |
| DV-GOV-07 | 吊销写审计 | P-SUPER-PLAT | 吊销 key | 记 ragforge.audit api_key_breakglass_revoke |
| DV-GOV-08 | 吊销后 key 失效 | P-SUPER-PLAT | 吊销后用该 key | 调用被拒 |
| DV-GOV-09 | 组织用户无治理入口 | P-OWNER | 看页面 | 无治理 tab/接口 403 |
| DV-GOV-10 | 治理不浏览全部 | P-SUPER-PLAT | 不搜索直接看 | 不展示全量 key（定向治理） |
| DV-GOV-11 | 吊销他组织 key 记录归属 | P-SUPER-PLAT | 吊销 B 的 key | 审计含组织/操作人 |
| DV-GOV-12 | 退破玻璃治理不可用 | P-SUPER | 退出全平台视图 | 治理接口拒绝 |

## DV-DOC 接口文档 / MCP（静态全局）

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| DV-DOC-01 | 接口文档组织归属为"自动绑定" | P-OWNER | 看接口文档 | "由 API key 自动绑定，无需传 X-Org-Id" |
| DV-DOC-02 | cURL 示例不含 X-Org-Id | P-OWNER | 看 cURL | 无 X-Org-Id 头 |
| DV-DOC-03 | MCP 配置不含 X-Org-Id | P-OWNER | 看 MCP 配置 | headers 仅 Authorization |
| DV-DOC-04 | 文档跨组织一致（静态） | P-OWNER vs P-ORGB | 两组织看文档 | 内容相同（静态全局） |
| DV-DOC-05 | Base URL 为线上域名 | P-OWNER | 看 Base URL | api.ragforge.net |
| DV-DOC-06 | 复制按钮可用 | P-OWNER | 点复制 | 复制正确内容 |
| DV-DOC-07 | key 自动绑定组织（行为验证） | P-OWNER | 用 A 的 key 调 /search | 仅返回 A（+公开）数据，无需传组织头 |
| DV-DOC-08 | 他组织 key 隔离 | P-ORGB | 用 B 的 key 调 | 仅 B 数据 |
| DV-DOC-09 | 停用 key 调用被拒 | P-OWNER | 停用后调 | 401/403 |
| DV-DOC-10 | 文档 tab 切换正常 | P-OWNER | 切三 tab | 无错乱 |
| DV-DOC-11 | MCP 适用客户端说明 | P-OWNER | 看 MCP tab | 列出适用客户端 |
| DV-DOC-12 | 普通成员可见文档/MCP | P-MEMBER | 进开发者中心 | 可见（下放后），凭证按 org admin |

---

# 模块九：性能诊断（/perf-probe）

复用 `listKb`（本组织 ∪ 公开）与 `search`（KbAccessGuard 兜底）批量压测；nav 仅组织 OWNER/ADMIN 可见。

## PF-RUN 压测执行

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| PF-RUN-01 | 单次压测执行 | P-OWNER | 跑一轮 | 返回逐次耗时 |
| PF-RUN-02 | 多次循环压测 | P-OWNER | 设循环次数 | 按次数执行 |
| PF-RUN-03 | 间隔参数生效 | P-OWNER | 设 intervalMs | 按间隔节流 |
| PF-RUN-04 | 间隔边界钳制 | P-OWNER | interval=0/超大 | 钳制到 0–5000 |
| PF-RUN-05 | 压测中止 | P-OWNER | 中途停止 | 可中止，结果保留已完成部分 |
| PF-RUN-06 | 多策略压测 | P-OWNER | hybrid/vector/bm25 | 各策略分别统计 |
| PF-RUN-07 | 空 query 校验 | P-OWNER | 空输入 | 拦截 |
| PF-RUN-08 | 压测不阻塞 UI | P-OWNER | 跑长压测 | UI 可响应，进度更新 |
| PF-RUN-09 | 失败请求计入统计 | P-OWNER | 制造失败 | 失败计数正确 |
| PF-RUN-10 | 压测结果可导出/查看 | P-OWNER | 看结果 | 数据完整可读 |
| PF-RUN-11 | 重复压测稳定 | P-OWNER | 多轮 | 数据合理可复现 |
| PF-RUN-12 | 大循环不内存泄漏 | P-OWNER | 大次数 | 不卡死/不崩 |

## PF-KB KB 范围与隔离

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| PF-KB-01 | 可选库=本组织 + 公开 | P-OWNER | 看下拉 | 含 A + 公开，不含 B |
| PF-KB-02 | 压测本组织库 | P-OWNER | 选 KB-A1 | 正常压测 |
| PF-KB-03 | 越权压测他组织库 | P-OWNER(若构造) | kbIds=[B库] | 403（search 兜底） |
| PF-KB-04 | 公开库压测 | P-OWNER | 选 KB-PUB | 正常 |
| PF-KB-05 | 切组织可选库刷新 | P-SUPER | A→B | 下拉换 B |
| PF-KB-06 | 空组织无可选库 | P-OWNER(组织C) | 看下拉 | 空/仅公开 |
| PF-KB-07 | 压测产生检索日志归属 | P-OWNER | 压测后查日志 | 归属本组织 |
| PF-KB-08 | 压测成本计入本组织 | P-OWNER | 压测后看成本 | 本组织检索成本增加 |
| PF-KB-09 | 删除库后不可选 | P-OWNER | 删库 | 下拉消失 |
| PF-KB-10 | 破玻璃可选全平台库 | P-SUPER-PLAT | 全平台 | 可选全平台库 |
| PF-KB-11 | 多库压测范围正确 | P-OWNER | 选多库 | 仅压测所选本组织库 |
| PF-KB-12 | 普通成员无性能诊断入口 | P-MEMBER | 看侧栏 | 无（orgRoles OWNER/ADMIN） |

## PF-STAT 延迟统计正确性

| 编号 | 用例名称 | 角色 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| PF-STAT-01 | p50/p95/avg 计算正确 | P-OWNER | 看统计 | 与逐次耗时分布吻合 |
| PF-STAT-02 | 统计非负 | P-OWNER | 看数值 | 均 ≥0 |
| PF-STAT-03 | p95 ≥ p50 | P-OWNER | 对比 | p95 ≥ p50 ≥ min |
| PF-STAT-04 | 单次样本统计 | P-OWNER | 跑 1 次 | 统计退化合理（p50=p95=该次） |
| PF-STAT-05 | 召回/精排分段耗时 | P-OWNER | 看分段 | 各段耗时合理，和≈总耗时 |
| PF-STAT-06 | 失败次数不计入耗时均值 | P-OWNER | 含失败 | 均值口径明确（仅成功或标注） |
| PF-STAT-07 | 统计随策略区分 | P-OWNER | 多策略 | 各策略独立统计 |
| PF-STAT-08 | 异常大延迟离群处理 | P-OWNER | 含离群点 | p95 体现，均值不被误导（口径一致） |
| PF-STAT-09 | 清空/重置统计 | P-OWNER | 重置 | 统计清零 |
| PF-STAT-10 | 统计与逐条明细一致 | P-OWNER | 核对 | 聚合统计与逐条数据可对账 |
| PF-STAT-11 | 单位毫秒一致 | P-OWNER | 看单位 | 统一 ms，无 s/ms 混用 |
| PF-STAT-12 | 切组织后统计清空/独立 | P-SUPER | A→B | 不残留 A 的压测统计 |

---

## 附：维护约定

- 新增模块沿用「**角色** / **数据前置** / **断言基线**」三段式；断言基线七项贯穿全部用例：
  1. 求和一致性　2. 组织隔离　3. 公开库纳入（注意驾驶舱**资产**为自有口径的例外）　4. 破玻璃聚合　5. 空态健壮　6. 边界与精度　7. 切换一致性与竞态。
- 编号前缀：质量看板 QD、模型&成本 MC、驾驶舱 DB、知识库 KB、检索 SE、应答 AN、评测 EV、开发者中心 DV、性能 PF。
- **口径差异提醒**：驾驶舱资产 = 本组织自有库（公开库他组织不计入）；质量看板 / 成本 / 检索 / 应答 / 评测 = 本组织 ∪ 公开库。此为设计差异，非缺陷，用例中已分别标注。
