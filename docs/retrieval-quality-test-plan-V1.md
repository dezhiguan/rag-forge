# RAGForge 检索质量端到端测试用例（Playwright）— V1（500 条）

> 目标：专门针对**检索链路质量**设计用例，深挖分块策略、检索策略、Query 改写、数据清洗、多模态、端到端召回/排序、组织/角色视角、鲁棒性与**友好报错**等各环节的 bug 与质量问题。
> 链路：`Query 改写(LLM) → 双路召回(pgvector 向量 + ES BM25) → RRF 融合 → Reranker 精排(qwen3-rerank) → TopK`。
> 说明：仅做**用例设计**，不含执行。允许**自造数据**（见 §0.3 合成语料）。贯穿要求：**任何失败分支的报错都必须对用户友好；裸错误码 / 堆栈 / 500 即记 bug。**

---

## 0. 测试约定

### 0.1 角色（Persona）

| 代号 | 身份 | 用途 |
|---|---|---|
| P-OWNER | 团队组织 A 的 OWNER | 主检索视角 |
| P-MEMBER | 团队组织 A 普通成员 | 角色一致性 |
| P-PERSONAL | 个人组织 owner | 个人库检索 |
| P-ORGB | 组织 B 的 OWNER | 跨组织隔离对照 |
| P-SUPER-PLAT | 平台超管 + 全平台视图 | 破玻璃聚合检索 |
| P-APIKEY-A | 组织 A 的 API key（X-API-Key 调 /search） | 外部调用视角 |

### 0.2 检索接口与参数（被测口径，依实现校准）

- `POST /api/v1/search`，`SearchRequest`：
  - `query`（必填，空 → 400 `QUERY_REQUIRED`）
  - `strategy`（默认 `vector`）：**`vector`** 纯向量(pgvector) / **`keyword`** 纯 BM25(ES，本文档"BM25"即指它) / **`hybrid`** 双路召回+RRF融合 / **`full`** 改写+hybrid+**精排(rerank)** / **`rewrite`** 改写+向量。**精排只在 `full` 触发**；非法 strategy 不报错而**回退默认**。
  - `vectorWeight`（默认 **0.55**，仅 `hybrid`/`full` 用）：`≥1` 退化纯向量、`≤0` 退化纯关键词、否则 RRF。
  - `topK`（默认 **8**，`@Min1 @Max50`）、`rerankTopN`（默认 **5**，`@Min1 @Max50`，仅 `full`）。
  - `kbIds`、`docIds`、`filter.chunkType`（按 chunk 类型过滤，OR 语义）。
- `POST /api/v1/search/by-image`，`ImageSearchRequest`：`queryImageBase64`（空 → 400 `IMAGE_QUERY_REQUIRED`）、`topK`、`kbIds`、`docIds`、`filter`。返回 strategy 标签 `image-vector`。
- **向量空间**：统一 **2560 维 `vl_vector`**（`qwen3-vl-embedding`，文本与图片同一空间，余弦 `<=>`）；无 HNSW 索引（>2000 维），顺序扫描。**RRF 常数 `RRF_K=10`**；召回 `recallTopK=max(topK*4,20)`；`full` 精排前每个文档**截断前 300 字**。
- 响应 `SearchResponse`：`results[]`、`latencyMs`、`strategy`、`rewrittenQueries`、`vectorLatencyMs`/`keywordLatencyMs`/`rerankLatencyMs`。`SearchResult` 含 `vectorScore`/`bm25Score`/`finalScore`/`chunkType`/`chunkModality`(TEXT|IMAGE)/`imageUrl`。
- 并发/超时（`ragforge.retrieval`）：keyword(20,5000ms) / vector(5,8000) / hybrid(5,12000) / full(1,15000) / rewrite(3,10000)；信号量满 → **429 "检索请求过多，请稍后重试"**，整体超时 → **504 "检索超时…"**。
- 鉴权：会话 JWT；外部用 `X-API-Key`（非 Bearer）。范围受 `KbAccessGuard`：API key 硬限定 `allowedKbIds`；会话用户 = 自有 ∪ ACL ∪ PUBLIC ∪ 组织可读(管理者=组织任意库、成员=ORG 可见库)；破玻璃=全部非 SYSTEM 库。

### 0.3 合成语料（自造数据，供精确断言）

> 每篇文档植入**唯一可断言事实句/锚点**，并配**黄金 query → 期望命中 chunk** 映射，用于 recall/precision/MRR/NDCG。

| 数据集 | 文档 | 关键设计 |
|---|---|---|
| **CORP-TXT** | D-TXT-1 纯文本技术文（Java 高并发，含唯一锚点句"灯塔项目的熔断阈值是 873 毫秒"作为可断言事实）；D-TXT-LONG 多章节超长文（清晰标题层级） | 文本召回/分块基线 |
| **CORP-MD** | D-MD Markdown（H1/H2/H3、有序/无序列表、代码块、管道表格） | 结构化分块 |
| **CORP-CODE** | D-CODE 源码文件（函数/注释/字符串） | 代码分块 |
| **CORP-TABLE** | D-XLSX 多 sheet 表格；D-CSV；D-DOCX-TABLE 含三线表的 docx | 表格感知分块 |
| **CORP-MIX** | D-DOCX-IMG 图文混合 docx（正文 + 内嵌图，图含文字"季度营收 1.2 亿"） | 图文混合 |
| **CORP-IMG** | D-IMG-OCR 扫描发票图（走 OCR）；D-IMG-VL 折线图照片（走 VL，无文字） | 纯图 OCR/VL 两路径 |
| **CORP-PDF** | D-PDF-TEXT 文本 PDF；D-PDF-SCAN 扫描 PDF（OCR） | PDF 文本 vs 扫描 |
| **CORP-LANG** | D-CN 中文、D-EN 英文、D-MIX-LANG 中英混排（同义概念） | 多语言/跨语言 |
| **CORP-NOISE** | D-NOISE 每页相同页眉页脚 + 水印 + 乱码块；D-DUP 含近重复段落；D-PII 含手机号/身份证/邮箱 | 清洗/去重/脱敏 |
| **CORP-NEG** | D-NEG 与黄金 query 主题无关的干扰文档（负样本） | 负样本不误命中 |

### 0.4 编号规则

`<维度字母>-<区>-<两位序号>`，例 `B-RRF-07`。维度：A 分块 / B 检索策略 / C 改写 / D 清洗 / E 多模态 / F 端到端 / G 组织角色 / H 鲁棒与友好报错。

### 0.5 质量断言基线（贯穿全部）

1. **命中正确性**：黄金 query 必须召回其期望 chunk（recall@k）。
2. **排序质量**：最相关 chunk 应在前列（precision@k / MRR / NDCG）。
3. **负样本**：无关 query/文档不得高分命中。
4. **分块完整**：语义/结构单元（表格、代码块、列表项）不被切碎或拼接错位。
5. **清洗有效**：噪声（页眉脚/水印/乱码/重复）不污染召回。
6. **隔离不降质**：跨组织/角色/API key 检索各自数据，质量一致且不泄漏。
7. **多模态对齐**：文本 query 与图片内容（OCR 文本 / VL 向量）按设计召回，维度不一致不导致漏召/错召。
8. **友好报错**：空/非法/越权/超时/限流/模态不匹配 → 友好中文提示，绝无裸码/堆栈/500。

---

# A. 分块策略质量（70）

## A-HEAD 按标题分块（12）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| A-HEAD-01 | 标题层级正确切分 | P-OWNER / D-TXT-LONG | 按标题分块入库后查 chunk | 每个 chunk 对应一个标题段，层级边界正确 |
| A-HEAD-02 | 子标题不与父标题混切 | / D-MD | 查 H2/H3 边界 | 子节独立成块，不跨级粘连 |
| A-HEAD-03 | 标题保留在 chunk 内 | / D-TXT-LONG | 看 chunk 文本 | 标题文字保留，便于命中 |
| A-HEAD-04 | 超长小节二次切分 | / 超长小节 | 查超长节 | 超出上限时在小节内再切，仍带标题上下文 |
| A-HEAD-05 | 无标题文档退化策略 | / 纯段落无标题 | 入库 | 退化到段落/窗口切分，不报错 |
| A-HEAD-06 | 标题命中提升召回 | / D-TXT-LONG | 用标题词 query | 对应小节 chunk 召回靠前 |
| A-HEAD-07 | 空小节处理 | / 含空标题节 | 入库 | 空节不产生空 chunk |
| A-HEAD-08 | Markdown 与正文标题一致 | / D-MD | 对比 | MD 标题与解析标题一致切分 |
| A-HEAD-09 | 标题编号/序号保留 | / 含"1.2.3"编号 | 查 chunk | 编号不丢，定位准确 |
| A-HEAD-10 | 连续多级标题不空切 | / H1>H2>H3 连续 | 查 chunk | 不产生只含标题的空内容块 |
| A-HEAD-11 | 分块数量合理 | / D-TXT-LONG | 统计 chunk 数 | 与小节数量量级一致，无碎块爆炸 |
| A-HEAD-12 | 重新分块切换策略一致 | / D-TXT-LONG | 文档详情重新分块为标题策略 | 结果与初次一致，幂等 |

## A-FIXED 固定窗口 + overlap（12）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| A-FIXED-01 | chunkSize 生效 | / D-TXT-1 | 设 chunkSize=300 | chunk 长度≈300（容差内） |
| A-FIXED-02 | overlap 生效 | / D-TXT-1 | 设 overlap=50 | 相邻 chunk 有约 50 字重叠 |
| A-FIXED-03 | overlap 保上下文不丢 | / 跨窗口事实句 | query 跨边界事实 | 重叠使事实句完整可召回 |
| A-FIXED-04 | overlap 过大导致重复召回 | / overlap 接近 size | 检索 | 应去重或不重复堆叠同内容 |
| A-FIXED-05 | size 过小碎块 | / size=20 | 入库 | 大量碎块，质量下降可观测（反例基线） |
| A-FIXED-06 | size 过大稀释 | / size=5000 | 检索 | 单块过大降低精排精度（反例基线） |
| A-FIXED-07 | size 边界值校验 | / size=0/负 | 提交 | 友好校验提示，非 500 |
| A-FIXED-08 | overlap≥size 校验 | / overlap>size | 提交 | 友好拦截 |
| A-FIXED-09 | 中文按字符切不乱码 | / 中文 | 切分 | 不在多字节中间截断 |
| A-FIXED-10 | 句界优先（若支持） | / D-TXT-1 | 看边界 | 尽量在句末切，不腰斩句子 |
| A-FIXED-11 | 仅固定/递归策略读 size | / D-MD 标题策略 | 设 size 后看 | 标题/语义策略忽略 size，提示明确 |
| A-FIXED-12 | 末尾残块处理 | / 长度非整除 | 切分 | 末尾残块保留，不丢内容 |

## A-RECUR 递归切分（10）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| A-RECUR-01 | 按分隔符递归 | / D-TXT-LONG | 递归切分 | 段落>句子>字符逐级回退 |
| A-RECUR-02 | 保段落完整 | / D-TXT-1 | 查 chunk | 尽量保留完整段落 |
| A-RECUR-03 | 超长段回退到句 | / 超长段 | 切分 | 段超限时按句切 |
| A-RECUR-04 | 列表项不被腰斩 | / D-MD 列表 | 切分 | 列表项保持完整 |
| A-RECUR-05 | 代码块整体保留 | / D-MD 代码块 | 切分 | 围栏代码块不被拆 |
| A-RECUR-06 | 分隔符缺失退字符 | / 无标点长串 | 切分 | 回退字符切，不死循环 |
| A-RECUR-07 | 递归与 size 协同 | / 设 size | 切分 | 受 size 约束，递归止于上限 |
| A-RECUR-08 | 空白/多换行归并 | / 多空行 | 切分 | 不产生空 chunk |
| A-RECUR-09 | 召回质量对比固定窗口 | / D-TXT-1 | 两策略召回同 query | 递归召回的片段更完整 |
| A-RECUR-10 | 重分块幂等 | / D-TXT-1 | 重复递归 | 结果稳定 |

## A-SEMANTIC 语义分块（10）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| A-SEMANTIC-01 | 按语义边界切 | / D-TXT-LONG | 语义分块 | 主题切换处分块 |
| A-SEMANTIC-02 | 同主题不过度切碎 | / 连贯论述 | 查 chunk | 同主题聚为一块 |
| A-SEMANTIC-03 | 主题突变正确分界 | / 拼接两主题 | 切分 | 两主题分属不同 chunk |
| A-SEMANTIC-04 | 语义块召回更精准 | / D-TXT-1 | 对比窗口策略 | 语义块 precision 更高 |
| A-SEMANTIC-05 | 短文档不空切 | / 短文 | 切分 | 1–2 块，合理 |
| A-SEMANTIC-06 | 计算成本可控 | / 大文 | 切分 | 不超时，有上限保护 |
| A-SEMANTIC-07 | 与 embedding 一致 | / D-TXT-1 | 检索 | 语义块向量更聚焦 |
| A-SEMANTIC-08 | 多语言语义切分 | / D-MIX-LANG | 切分 | 跨语言主题边界正确 |
| A-SEMANTIC-09 | 失败回退 | / 异常输入 | 切分 | 回退到递归/窗口，不报错 |
| A-SEMANTIC-10 | 重分块稳定 | / D-TXT-1 | 重复 | 结果稳定可复现 |

## A-TABLE 表格感知（14）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| A-TABLE-01 | 表格不被切碎 | / D-DOCX-TABLE | 切分 | 整表或整行成块，不腰斩单元格 |
| A-TABLE-02 | 表头随每块保留 | / D-XLSX | 查 chunk | 行块携带表头，便于理解 |
| A-TABLE-03 | 行列对齐不错位 | / D-TABLE | 查内容 | 单元格归属正确 |
| A-TABLE-04 | 大表分块策略 | / 千行表 | 切分 | 按行分批，带表头，无超大块 |
| A-TABLE-05 | 合并单元格处理 | / 含合并格 | 切分 | 合并语义保留或合理展开 |
| A-TABLE-06 | 表格内数值可检索 | / D-XLSX | query 某数值 | 命中对应行块 |
| A-TABLE-07 | 表格 + 正文混排 | / D-DOCX-TABLE | 切分 | 表与正文分块清晰 |
| A-TABLE-08 | CSV 解析为表 | / D-CSV | 入库 | 按行/表结构分块 |
| A-TABLE-09 | 多 sheet 区分 | / D-XLSX | 查来源 | 各 sheet 标识区分 |
| A-TABLE-10 | 空表/单行表 | / 边界表 | 入库 | 不报错，不产空块 |
| A-TABLE-11 | 表格 query 召回排序 | / D-TABLE | 检索 | 相关行块靠前 |
| A-TABLE-12 | 非表格被误判为表 | / 伪表(空格对齐) | 切分 | 不误切，降级文本处理 |
| A-TABLE-13 | 表格转文本可读性 | / D-TABLE | 看 chunk 文本 | 转写保留行列关系（如 Markdown 表） |
| A-TABLE-14 | 表格命中片段展示友好 | / D-TABLE | 看检索结果 | 表格片段展示清晰，非乱码 |

## A-STRUCT 代码/列表/公式等结构（12）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| A-STRUCT-01 | 代码块整体保留 | / D-CODE | 切分 | 函数/代码块不被拆断 |
| A-STRUCT-02 | 代码注释可检索 | / D-CODE | query 注释词 | 命中对应代码块 |
| A-STRUCT-03 | 有序列表不腰斩 | / D-MD | 切分 | 列表项完整 |
| A-STRUCT-04 | 嵌套列表层级保留 | / 嵌套列表 | 切分 | 层级结构不乱 |
| A-STRUCT-05 | 公式/LaTeX 不破坏 | / 含公式 | 切分 | 公式整体保留 |
| A-STRUCT-06 | 引用块/注脚处理 | / 含引用 | 切分 | 引用归属正确 |
| A-STRUCT-07 | 链接/URL 不截断 | / 含长 URL | 切分 | URL 完整 |
| A-STRUCT-08 | 标点/特殊符号边界 | / 中英标点 | 切分 | 不在标点中间错切 |
| A-STRUCT-09 | Emoji/特殊 Unicode | / 含 emoji | 切分 | 不乱码不截断多字节 |
| A-STRUCT-10 | 代码 query 精确召回 | / D-CODE | query 函数名 | BM25 精确命中 |
| A-STRUCT-11 | 混合结构文档 | / 正文+代码+表 | 切分 | 各结构分块合理 |
| A-STRUCT-12 | 结构片段展示友好 | / D-CODE | 看结果 | 代码片段保留格式，可读 |

---

# B. 检索策略质量（80）

## B-MODE 三种检索模式（16）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| B-MODE-01 | hybrid 默认可用 | P-OWNER / CORP-TXT | 默认检索 | 返回融合结果 |
| B-MODE-02 | vector 语义召回 | / 近义表述 query | vector | 命中语义相近 chunk（非字面） |
| B-MODE-03 | bm25 精确召回 | / 稀有专有名词 | bm25 | 精确命中含该词 chunk |
| B-MODE-04 | hybrid 优于单路（语义+关键词混合 query） | / 混合 query | 三路对比 | hybrid 综合召回最佳 |
| B-MODE-05 | vector 对拼写错误更鲁棒 | / 错拼 query | vector vs bm25 | vector 仍可召回，bm25 漏 |
| B-MODE-06 | bm25 对稀有词更准 | / 罕见术语 | bm25 vs vector | bm25 精确，vector 可能漂移 |
| B-MODE-07 | 同义词召回 | / 同义 query | vector | 命中同义表述 |
| B-MODE-08 | 否定语义 | / "不包含 X" | 各模式 | 不被 X 高分误召（观测局限并记录） |
| B-MODE-09 | 短 query（1–2 词） | / 短 query | hybrid | 仍有合理召回 |
| B-MODE-10 | 长 query（整段） | / 长 query | hybrid | 不超时，召回主旨相关 |
| B-MODE-11 | 模式参数非法回退 | / strategy=xxx | 提交 | 友好提示或回退默认，非 500 |
| B-MODE-12 | 空结果模式一致性 | / 库外 query | 三模式 | 均空结果 + 友好空态 |
| B-MODE-13 | 跨语言召回（中查英） | / D-EN | 中文 query | vector 跨语言命中（若 embedding 支持） |
| B-MODE-14 | 大小写/全半角 | / 混合大小写 | bm25 | 归一化后命中 |
| B-MODE-15 | 停用词处理 | / 含大量停用词 | bm25 | 停用词不主导排序 |
| B-MODE-16 | 模式切换结果可解释 | / 同 query | 三模式对比 | 差异符合各模式特性 |

## B-RRF 融合（14）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| B-RRF-01 | 两路命中融合提名 | / 双路都命中 | hybrid | 双路共识 chunk 排名提升 |
| B-RRF-02 | 单路独有不丢 | / 仅向量命中 | hybrid | 向量独有结果仍进入候选 |
| B-RRF-03 | 融合去重 | / 同 chunk 两路命中 | hybrid | 同 chunk 不重复出现 |
| B-RRF-04 | 融合排序稳定 | / 同 query 多次 | hybrid | 排序可复现 |
| B-RRF-05 | 召回集大小合理 | / 大库 | hybrid | 融合前各路召回 N 合理，不过小漏召 |
| B-RRF-06 | 一路为空仍可用 | / bm25 空命中 | hybrid | 退化为另一路，不报错 |
| B-RRF-07 | 两路皆空 | / 库外 query | hybrid | 空结果 + 友好空态 |
| B-RRF-08 | 融合不被单路霸榜 | / 一路大量弱相关 | hybrid | 弱相关不挤掉强相关 |
| B-RRF-09 | 权重对融合的影响 | / vectorWeight 变化 | hybrid | 排序随权重平滑变化 |
| B-RRF-10 | 融合后进入精排数量 | / 大候选 | hybrid | 截断到 rerank 输入上限合理 |
| B-RRF-11 | 融合分数单调性 | / 观测分数 | hybrid | 排名越前融合分越高 |
| B-RRF-12 | 长尾文档可被融合提名 | / 长尾相关 | hybrid | 不因单路弱而完全丢失 |
| B-RRF-13 | 重复近似内容融合 | / D-DUP | hybrid | 近重复合并/去重，不刷屏 |
| B-RRF-14 | 融合与最终 topK 一致 | / topK=5 | hybrid | 最终返回为融合+精排后的前 5 |

## B-RERANK 精排（14）

> 口径：**精排(qwen3-rerank)仅在 `strategy=full` 触发**（hybrid 只到 RRF）；精排前每个文档**截断前 300 字**送 rerank（潜在质量风险点）；失败时优雅回退原序（合成分 `1.0 - i*0.01`）。对比"有无精排"即对比 `full` vs `hybrid`。

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| B-RERANK-01 | 精排提升相关性 | / D-TXT-1 | full vs hybrid 对比 | full 把最相关 chunk 排到前列 |
| B-RERANK-15 | 长文档前 300 字截断风险 | / 关键信息在 300 字后的长 chunk | full 检索 | 评估截断是否漏掉关键相关性、记录质量风险 |
| B-RERANK-02 | 精排纠正召回错序 | / 召回错序 | rerank | 纠正为相关性序 |
| B-RERANK-03 | 精排分数单调 | / 观测 | rerank | rerank 分降序与展示序一致 |
| B-RERANK-04 | 精排输入截断 | / 大候选 | rerank | 仅对前 N 候选精排，性能可控 |
| B-RERANK-05 | 精排对长 chunk 公平 | / 长短混合 | rerank | 不偏向长/短，看相关性 |
| B-RERANK-06 | 精排跨语言 | / D-MIX-LANG | rerank | 跨语言相关性合理 |
| B-RERANK-07 | 精排失败降级 | / 模型超时 | rerank | 降级用融合序 + 友好提示，不 500 |
| B-RERANK-08 | 关闭精排对比 | / 配置无精排 | 对比 | 有精排时 precision@k 更高 |
| B-RERANK-09 | 精排去噪 | / D-NOISE | rerank | 噪声块被压到后面 |
| B-RERANK-10 | 精排负样本压制 | / CORP-NEG | rerank | 无关块低分靠后 |
| B-RERANK-11 | 精排稳定性 | / 同 query | rerank | 多次结果稳定 |
| B-RERANK-12 | 精排 top1 准确率 | / 黄金 query 集 | rerank | top1 命中率达标（基线指标） |
| B-RERANK-13 | 精排计量正确 | / 检索后 | 看成本 | rerank token/调用计入本组织成本 |
| B-RERANK-14 | 精排与 topK 协同 | / topK 变化 | rerank | 返回数=topK，序为精排序 |

## B-TOPK 截断（10）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| B-TOPK-01 | topK=5 返回 5 | / 充足候选 | topK=5 | 恰返回 5 |
| B-TOPK-02 | topK=1 | / | topK=1 | 返回最相关 1 |
| B-TOPK-03 | topK 取上限 50 超候选数 | / 少候选 | topK=50 | 返回全部可用，不补空 |
| B-TOPK-04 | topK=0/负校验 | / | topK=0 | 400 友好校验（@Min1），非 500 |
| B-TOPK-05 | topK 超上限 50 | / topK=10000 | 提交 | 400 友好提示（@Max50），不 OOM |
| B-TOPK-06 | topK 不影响排序仅截断 | / | 比 5 vs 10 | 前 5 一致 |
| B-TOPK-07 | 默认 topK | / 不传 | 检索 | 取合理默认值 |
| B-TOPK-08 | topK 与去重协同 | / D-DUP | topK=5 | 5 条为去重后不同内容 |
| B-TOPK-09 | topK 性能 | / 大库 topK 大 | 检索 | 延迟可接受 |
| B-TOPK-10 | topK 边界展示 | / topK=5 命中 3 | 看结果 | 展示 3 条 + 合理提示，不空填 |

## B-WEIGHT 向量权重（10）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| B-WEIGHT-01 | weight=1 纯向量 | / | weight=1 | 等价 vector 模式 |
| B-WEIGHT-02 | weight=0 纯 BM25 | / | weight=0 | 等价 bm25 模式 |
| B-WEIGHT-03 | weight=0.5 均衡 | / | weight=0.5 | 两路均衡融合 |
| B-WEIGHT-04 | 权重平滑变化 | / 0→1 扫描 | 观测排序 | 排序随权重单调演化 |
| B-WEIGHT-05 | 关键词型 query 低权更优 | / 专名 query | 调权 | 偏 BM25 时更准 |
| B-WEIGHT-06 | 语义型 query 高权更优 | / 近义 query | 调权 | 偏向量时更准 |
| B-WEIGHT-07 | 非法权重回退 | / weight=2/-1 | 提交 | 钳制/友好提示 |
| B-WEIGHT-08 | 权重默认值 | / 不传 | 检索 | 合理默认 |
| B-WEIGHT-09 | 权重与精排叠加 | / 调权 | rerank | 精排在融合之上，最终相关性不倒退 |
| B-WEIGHT-10 | 权重可复现 | / 同权同 query | 多次 | 结果稳定 |

## B-SEMANTIC 语义/词法难例（16）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| B-SEMANTIC-01 | 同义词 | / "高并发"vs"大流量" | vector | 命中同义 |
| B-SEMANTIC-02 | 近义但不同义 | / 易混词 | hybrid | 不误召不相关 |
| B-SEMANTIC-03 | 一词多义 | / 多义词 | hybrid | 按上下文召回合理 |
| B-SEMANTIC-04 | 缩写/全称 | / "K8s"vs"Kubernetes" | hybrid | 两者互召 |
| B-SEMANTIC-05 | 拼写错误 | / 错拼 | vector | 仍召回 |
| B-SEMANTIC-06 | 中英混写 query | / "JVM 调优" | hybrid | 正常召回 |
| B-SEMANTIC-07 | 数字/单位 | / "1.2 亿"vs"120000000" | hybrid | 命中对应（记录局限） |
| B-SEMANTIC-08 | 否定/排除 | / "除了 X 以外" | hybrid | 记录否定召回局限 |
| B-SEMANTIC-09 | 长尾稀有词 | / 稀有专名 | bm25 | 精确命中 |
| B-SEMANTIC-10 | 停用词主导 query | / 全停用词 | hybrid | 不返回噪声/友好空态 |
| B-SEMANTIC-11 | 同形异义跨文档 | / 多文档同词 | hybrid | 按相关性排序，不串文档语义 |
| B-SEMANTIC-12 | query 含 KB 内不存在概念 | / 库外概念 | hybrid | 空/低分 + 友好空态，不幻觉命中 |
| B-SEMANTIC-13 | 口语化 query | / 口语提问 | hybrid | 召回正式表述 chunk |
| B-SEMANTIC-14 | 多意图复合 query | / 含两问 | hybrid | 至少召回主意图相关 |
| B-SEMANTIC-15 | 大小写/繁简 | / 繁体 query | hybrid | 繁简归一命中 |
| B-SEMANTIC-16 | 极短/极长对比 | / 1 字 vs 整段 | hybrid | 两端都不崩，结果合理 |

---

# C. Query 改写质量（40）

## C-EXPAND 改写/扩写（14）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| C-EXPAND-01 | 改写补全省略 | / 省略主语 query | 检索 | 改写补全后召回更准 |
| C-EXPAND-02 | 改写扩同义 | / 单一表述 | 检索 | 扩展同义提升召回 |
| C-EXPAND-03 | 改写不改变意图 | / 明确 query | 对比 | 意图不漂移 |
| C-EXPAND-04 | 改写前后召回对比 | / D-TXT-1 | 开/关改写 | 改写后 recall 不降、通常升 |
| C-EXPAND-05 | 改写引入噪声反例 | / 含歧义 | 检索 | 改写过度引噪时可观测并记录 |
| C-EXPAND-06 | 改写澄清歧义 | / 多义 query | 检索 | 按主语境澄清 |
| C-EXPAND-07 | 短 query 扩展 | / 1–2 词 | 检索 | 扩展为可检索表述 |
| C-EXPAND-08 | 长 query 提炼 | / 冗长 query | 检索 | 提炼关键意图 |
| C-EXPAND-09 | 改写失败降级 | / LLM 超时 | 检索 | 降级用原 query + 友好提示，不 500 |
| C-EXPAND-10 | 改写计量正确 | / 检索后 | 成本 | REWRITE token 计入本组织 |
| C-EXPAND-11 | 改写可关闭 | / 配置 | 检索 | 可走原始 query |
| C-EXPAND-12 | 改写稳定性 | / 同 query | 多次 | 改写结果大致稳定 |
| C-EXPAND-13 | 改写不泄漏跨库信息 | / 多组织 | 检索 | 改写仅基于 query，不引他组织内容 |
| C-EXPAND-14 | 改写对负样本无效放大 | / 库外 query | 检索 | 改写不把无关 query 强行命中 |

## C-MULTILANG 多语言改写（12）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| C-MULTILANG-01 | 中文 query 改写 | / D-CN | 检索 | 改写保持中文语义 |
| C-MULTILANG-02 | 英文 query 改写 | / D-EN | 检索 | 改写保持英文语义 |
| C-MULTILANG-03 | 中查英库（跨语言） | / D-EN | 中文 query | 改写/向量实现跨语言召回 |
| C-MULTILANG-04 | 英查中库 | / D-CN | 英文 query | 跨语言召回 |
| C-MULTILANG-05 | 中英混排 query | / D-MIX-LANG | 检索 | 正常处理 |
| C-MULTILANG-06 | 繁简差异 | / 繁体 | 检索 | 归一召回 |
| C-MULTILANG-07 | 多语言同义概念 | / 概念互译 | 检索 | 跨语言命中同概念 |
| C-MULTILANG-08 | 语言判定失败降级 | / 混杂字符 | 检索 | 降级不报错 |
| C-MULTILANG-09 | 专有名词不被错译 | / 品牌/术语 | 改写 | 专名保留不乱译 |
| C-MULTILANG-10 | 代码/符号不被翻译 | / 含代码 | 改写 | 代码原样保留 |
| C-MULTILANG-11 | 多语言空结果友好 | / 库外语言 | 检索 | 友好空态 |
| C-MULTILANG-12 | 跨语言排序合理 | / 双语库 | 检索 | 相关性序合理 |

## C-NEG 改写负面/鲁棒（14）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| C-NEG-01 | 注入式 query | / "忽略指令…" | 改写 | 不被提示注入劫持 |
| C-NEG-02 | 超长 query 改写 | / 超长 | 改写 | 截断/拒绝，友好提示 |
| C-NEG-03 | 空 query | / 空 | 提交 | 友好"请输入检索查询"，非 500 |
| C-NEG-04 | 纯符号/乱码 query | / "###@@@" | 改写 | 不崩，友好空态 |
| C-NEG-05 | 敏感词 query | / 敏感 | 改写 | 合规处理，提示友好 |
| C-NEG-06 | 改写超时熔断 | / 慢响应 | 检索 | 熔断降级 + 友好提示 |
| C-NEG-07 | 改写返回空 | / 模型空返回 | 检索 | 回退原 query |
| C-NEG-08 | 改写返回超长 | / 异常膨胀 | 检索 | 截断保护 |
| C-NEG-09 | 改写引入错误实体 | / 易混实体 | 检索 | 可观测并记录质量风险 |
| C-NEG-10 | 高频重复 query 缓存一致 | / 同 query | 连发 | 若有缓存，结果一致 |
| C-NEG-11 | 改写与组织上下文无关 | / 切组织 | 检索 | 改写只看 query 文本 |
| C-NEG-12 | 改写失败不阻断检索 | / 改写挂 | 检索 | 仍能用原 query 返回结果 |
| C-NEG-13 | 改写错误提示友好 | / 触发错误 | 看提示 | 友好中文，不暴露内部 |
| C-NEG-14 | 改写日志不含敏感原文 | / 含 PII query | 日志 | 不明文记 PII |

---

# D. 数据清洗质量（55）

> 清洗管道按序：**`L1_NORMALIZE`**（NFKC 归一、CRLF→LF、去控制符、空白/多换行折叠）→ **`L2_DENOISE`**（去重复页眉页脚[出现率≥50%]、目录 TOC、水印 confidential/机密 等，产出 `RemovedRegion`）→ **`L3_PII_MASK`**（EMAIL/ID_CARD[校验位]/PHONE[`1[3-9]\d{9}`]/BANK_CARD；策略 `MASK`/`HASH`/`REJECT`，REJECT→400 `PII_REJECTED`）。**无 L4 清洗器**（`l4Enabled` 仅占位）。`skipClean` 可整体跳过。

## D-NOISE 去噪/页眉页脚/水印（L2_DENOISE）（14）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| D-NOISE-01 | 重复页眉去除 | / D-NOISE | 入库后查 chunk | 每页页眉不重复入库 |
| D-NOISE-02 | 页脚/页码去除 | / D-NOISE | 查 chunk | 页码噪声被清 |
| D-NOISE-03 | 水印文字去除 | / D-NOISE | 查 chunk | 水印不污染正文 |
| D-NOISE-04 | 乱码块剔除 | / D-NOISE | 查 chunk | 乱码段被清或隔离 |
| D-NOISE-05 | 页眉不被误召 | / D-NOISE | query 页眉词 | 不返回页眉噪声 |
| D-NOISE-06 | 清洗不误删正文 | / D-NOISE | 对比正文 | 正文锚点句保留 |
| D-NOISE-07 | 目录/索引页处理 | / 含目录 | 入库 | 目录不喧宾夺主 |
| D-NOISE-08 | 参考文献块处理 | / 含参考文献 | 入库 | 按设计保留/降权 |
| D-NOISE-09 | 多余空白/换行归一 | / 多空行 | 查 chunk | 归一，不产空块 |
| D-NOISE-10 | 控制字符清理 | / 含  等 | 入库 | 控制符清理，不入库异常 |
| D-NOISE-11 | 清洗前后召回对比 | / D-NOISE | 对比 | 清洗后 precision 提升 |
| D-NOISE-12 | 清洗幂等 | / D-NOISE | 重入库 | 结果一致 |
| D-NOISE-13 | 超大噪声文档 | / 大量噪声 | 入库 | 不超时，清洗有效 |
| D-NOISE-14 | 清洗失败友好 | / 异常编码 | 入库 | 友好错误，非 500 |

## D-DEDUP 去重（12）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| D-DEDUP-01 | 完全重复段去重 | / D-DUP | 入库 | 重复段不重复入库 |
| D-DEDUP-02 | 近重复段处理 | / D-DUP | 入库/检索 | 近重复合并或检索去重 |
| D-DEDUP-03 | 跨文档重复 | / 两文档同段 | 检索 | 结果不重复刷屏 |
| D-DEDUP-04 | 去重不误删差异内容 | / 相似但不同 | 入库 | 有差异的保留 |
| D-DEDUP-05 | 检索结果去重 | / D-DUP | 检索 topK | topK 为不同内容 |
| D-DEDUP-06 | 去重保留最佳来源 | / 多来源同内容 | 检索 | 保留最相关/最新来源 |
| D-DEDUP-07 | 去重统计正确 | / D-DUP | 看 chunkCount | 与去重后一致 |
| D-DEDUP-08 | 大规模重复性能 | / 海量重复 | 入库 | 不爆量 |
| D-DEDUP-09 | 去重阈值合理 | / 边界相似 | 入库 | 阈值不过激/不漏 |
| D-DEDUP-10 | 重新分块后去重一致 | / D-DUP | 重分块 | 仍去重 |
| D-DEDUP-11 | 去重跨组织不串 | / A、B 同内容 | 检索 | 各组织独立去重，不串库 |
| D-DEDUP-12 | 去重对排序无副作用 | / D-DUP | 检索 | 去重后排序仍相关 |

## D-NORM 归一/标签清理（14）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| D-NORM-01 | HTML 标签清理 | / 含 HTML | 入库 | 标签去除留文本 |
| D-NORM-02 | Markdown 语法保留语义 | / D-MD | 入库 | 标记转纯文本不丢内容 |
| D-NORM-03 | 转义字符还原 | / &amp; 等 | 入库 | 实体还原为字符 |
| D-NORM-04 | 全半角归一 | / 全角符号 | 入库/检索 | 归一后可命中 |
| D-NORM-05 | 大小写归一（检索侧） | / 大小写混 | bm25 | 命中一致 |
| D-NORM-06 | 多余标点归并 | / "！！！" | 入库 | 归并，不影响语义 |
| D-NORM-07 | URL/邮箱保形 | / 含 URL | 入库 | 保留可检索形态 |
| D-NORM-08 | 表情/特殊 Unicode | / emoji | 入库 | 不破坏编码 |
| D-NORM-09 | 换行/制表归一 | / \t\r\n 混 | 入库 | 归一为合理空白 |
| D-NORM-10 | 脚本/样式块剔除 | / 含 script/style | 入库 | 完全剔除 |
| D-NORM-11 | 编码识别（GBK/UTF-8） | / 不同编码 | 入库 | 正确识别不乱码 |
| D-NORM-12 | 归一前后检索一致性 | / 归一文档 | 检索 | 召回更稳定 |
| D-NORM-13 | 归一不破坏代码 | / 代码块 | 入库 | 代码原义保留 |
| D-NORM-14 | 归一异常友好 | / 异常编码 | 入库 | 友好报错 |

## D-PII 脱敏对检索的影响（15）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| D-PII-01 | 手机号脱敏 | / D-PII | 入库后查 chunk | 手机号被掩码 |
| D-PII-02 | 身份证脱敏 | / D-PII | 查 chunk | 身份证掩码 |
| D-PII-03 | 邮箱脱敏 | / D-PII | 查 chunk | 邮箱按策略掩码 |
| D-PII-04 | 银行卡/敏感号 | / D-PII | 查 chunk | 掩码 |
| D-PII-05 | 脱敏不破坏上下文检索 | / D-PII | query 上下文 | 周边正文仍可召回 |
| D-PII-06 | 脱敏后不可反查原值 | / D-PII | query 原手机号 | 命中不出明文 |
| D-PII-07 | 误脱敏正常数字 | / 含订单号 | 入库 | 不误掩非 PII（或可解释） |
| D-PII-08 | 脱敏跨语言 | / 中英 PII | 入库 | 两语言均脱敏 |
| D-PII-09 | OCR 文本中的 PII | / D-IMG-OCR 含证件 | 入库 | OCR 出的 PII 也脱敏 |
| D-PII-10 | 脱敏日志不留明文 | / D-PII | 日志 | 处理日志无明文 PII |
| D-PII-11 | 脱敏对召回率影响 | / D-PII | 对比 | 召回不显著下降 |
| D-PII-12 | 检索结果展示已脱敏 | / D-PII | 看结果片段 | 展示为掩码态 |
| D-PII-13 | 脱敏开关一致 | / 配置 | 入库 | 开关行为一致可控 |
| D-PII-14 | 脱敏失败不阻断入库 | / 异常 | 入库 | 降级 + 友好告警 |
| D-PII-15 | 跨组织 PII 不泄漏 | / A 库 PII | P-ORGB 检索 | 越权不可见 |

---

# E. 多模态 × 文档类型（90）

## E-TYPE 文档类型矩阵（20）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| E-TYPE-01 | 纯文本 txt 入库检索 | / D-TXT-1 | 全链路 | 召回正确 |
| E-TYPE-02 | Markdown 入库检索 | / D-MD | 全链路 | 结构保留，召回正确 |
| E-TYPE-03 | 文本 PDF | / D-PDF-TEXT | 全链路 | 文本提取正确 |
| E-TYPE-04 | 扫描 PDF（OCR） | / D-PDF-SCAN | 全链路 | OCR 文本可检索 |
| E-TYPE-05 | docx 纯文本 | / docx | 全链路 | 正确 |
| E-TYPE-06 | docx 图文混合 | / D-DOCX-IMG | 全链路 | 正文+图各自处理 |
| E-TYPE-07 | xlsx 表格 | / D-XLSX | 全链路 | 表格分块检索 |
| E-TYPE-08 | csv | / D-CSV | 全链路 | 行/表检索 |
| E-TYPE-09 | 纯图片 PNG | / D-IMG-VL | 全链路 | 按 imageProcessingMode 处理 |
| E-TYPE-10 | 纯图片含文字（OCR） | / D-IMG-OCR | OCR 模式 | 图中文字可检索 |
| E-TYPE-11 | 代码文件 | / D-CODE | 全链路 | 代码检索 |
| E-TYPE-12 | 多语言文档 | / D-MIX-LANG | 全链路 | 多语言检索 |
| E-TYPE-13 | 超大文件 | / 大 PDF | 入库 | 不超时/分批，进度正确 |
| E-TYPE-14 | 空文件/0 字节 | / 空 | 入库 | 友好拒绝，非 500 |
| E-TYPE-15 | 损坏文件 | / 坏 PDF | 入库 | 友好报错，标记失败可重试 |
| E-TYPE-16 | 不支持类型 | / .exe | 入库 | 友好"不支持的类型"，非 500 |
| E-TYPE-17 | 超大图片 | / 高分辨率图 | 入库 | 压缩/限制，处理成功 |
| E-TYPE-18 | 混合批量上传 | / 多类型 | 批量 | 各类型分别正确处理 |
| E-TYPE-19 | 文件名/中文名 | / 中文名文件 | 入库 | 不乱码 |
| E-TYPE-20 | 类型 × 检索质量基线 | / 各类型黄金 query | 检索 | 各类型 recall 达标 |

## E-OCR OCR 路径（16）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| E-OCR-01 | OCR 提取清晰扫描件 | / D-PDF-SCAN | OCR | 文本准确 |
| E-OCR-02 | OCR 模式开关 | / imageProcessingMode=OCR | 入库 | 走 OCR 路径 |
| E-OCR-03 | 图中文字可检索 | / D-IMG-OCR | query 图中词 | 命中 |
| E-OCR-04 | OCR 多语言 | / 中英扫描 | OCR | 两语言识别 |
| E-OCR-05 | OCR 表格还原 | / 扫描表格 | OCR | 行列尽量还原 |
| E-OCR-06 | 模糊/低质图 | / 低清图 | OCR | 尽力识别 + 置信度/降级标注 |
| E-OCR-07 | 旋转/倾斜图 | / 倾斜扫描 | OCR | 纠偏识别 |
| E-OCR-08 | OCR 失败降级 | / 无法识别 | OCR | 友好标注失败，可重试 |
| E-OCR-09 | OCR 文本清洗 | / 噪声扫描 | OCR+清洗 | OCR 噪声被清 |
| E-OCR-10 | OCR PII 脱敏 | / 证件扫描 | OCR | PII 脱敏 |
| E-OCR-11 | OCR 计量正确 | / 入库后 | 成本 | OCR token 计入本组织 |
| E-OCR-12 | OCR 超时熔断 | / 慢响应 | 入库 | 熔断 + 友好提示 |
| E-OCR-13 | OCR 分块合理 | / 长扫描文 | 分块 | 不碎不超大 |
| E-OCR-14 | OCR 召回排序 | / D-PDF-SCAN | 检索 | 相关段靠前 |
| E-OCR-15 | OCR 与原图溯源 | / 命中 | 看结果 | 可溯源到原图/页 |
| E-OCR-16 | OCR 空白图 | / 无文字图 | OCR | 友好提示无文本，非 500 |

## E-VL 视觉 embedding 路径（16）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| E-VL-01 | VL 模式开关 | / imageProcessingMode=VL | 入库 | 走视觉 embedding（2560 维） |
| E-VL-02 | 以图检索命中相似图 | / D-IMG-VL | /search/by-image | 命中视觉相似图 chunk |
| E-VL-03 | 文本 query 命中图（VL） | / D-IMG-VL | 文本 query | 按设计是否跨模态命中（记录能力边界） |
| E-VL-04 | 无文字图表 | / 折线图 | VL | 可被以图检索召回 |
| E-VL-05 | VL 维度 2560 一致 | / D-IMG-VL | 入库 | 向量维度=2560，写入正确 |
| E-VL-06 | VL 与文本不同空间 | / 混合库 | 检索 | 不因维度差异崩；融合策略明确 |
| E-VL-07 | 以图检索 topK | / 多相似图 | by-image | topK 为相似度序 |
| E-VL-08 | VL 失败降级 | / 异常图 | VL | 友好降级，非 500 |
| E-VL-09 | VL 计量正确 | / 入库后 | 成本 | VL token 计入本组织 |
| E-VL-10 | 以图检索越权隔离 | / P-ORGB | by-image 他组织图库 | 不泄漏 |
| E-VL-11 | 大图/多图文档 | / 多图 docx | 入库 | 每图独立 embedding |
| E-VL-12 | VL 相似度阈值 | / 不相似图 | by-image | 低相似不误召 |
| E-VL-13 | VL query 图非法 | / 非图文件当 query | by-image | 友好"请选择图片" |
| E-VL-14 | VL + OCR 混合策略 | / 含文字图表 | ON 模式 | OCR 文本 + VL 向量都建（若 ON 双建） |
| E-VL-15 | VL 结果可溯源 | / 命中图 | 看结果 | 溯源到原图 |
| E-VL-16 | VL 稳定性 | / 同图 | 多次 | 向量/结果稳定 |

## E-MIX 图文混合（16）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| E-MIX-01 | docx 内嵌图提取 | / D-DOCX-IMG | 入库 | 图被抽取并处理 |
| E-MIX-02 | 正文与图分别建块 | / D-DOCX-IMG | 查 chunk | 文本块 + 图块共存 |
| E-MIX-03 | 图旁正文关联 | / D-DOCX-IMG | 检索 | 图与说明文字关联召回 |
| E-MIX-04 | 文本 query 命中图内文字 | / 图含"1.2 亿" | OCR | 命中图块 |
| E-MIX-05 | 图文同页排序 | / D-DOCX-IMG | 检索 | 相关图/文合理同现 |
| E-MIX-06 | 图丢失/坏图 | / 坏内嵌图 | 入库 | 跳过坏图，正文仍入库 |
| E-MIX-07 | 多图文档分别溯源 | / 多图 | 检索 | 各图独立溯源 |
| E-MIX-08 | 图标题/图注处理 | / 含图注 | 入库 | 图注作为文本可检索 |
| E-MIX-09 | 图文混合 PDF | / 图文 PDF | 入库 | 图文都处理 |
| E-MIX-10 | 图文检索结果展示 | / D-DOCX-IMG | 看结果 | 图块展示缩略/可点，文本块正常 |
| E-MIX-11 | 图文混合分块边界 | / D-DOCX-IMG | 分块 | 图不打断正文语义块错位 |
| E-MIX-12 | imageProcessingMode=OFF | / D-DOCX-IMG | OFF | 图被跳过，仅正文 |
| E-MIX-13 | 图文成本归属 | / 入库后 | 成本 | 文本/OCR/VL 分别计入本组织 |
| E-MIX-14 | 图文越权隔离 | / P-ORGB | 检索 | 不泄漏他组织图文 |
| E-MIX-15 | 图文重分块一致 | / D-DOCX-IMG | 重分块 | 图文块稳定 |
| E-MIX-16 | 图文混合友好报错 | / 部分失败 | 入库 | 部分图失败给友好提示，不整篇失败 |

## E-DIM 统一向量空间与跨模态对齐（12）

> 实现：文本与图片**统一编码进 2560 维 `vl_vector`**（qwen3-vl-embedding，同一空间），余弦 `<=>` 检索；`content_vector(1024)`/`image_vector(1024)` 为遗留列，**不在线检索路径**；无 HNSW 索引（>2000 维），顺序扫描。

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| E-DIM-01 | 文本与图片同为 2560 维 | / 混合库 | 入库后查向量列 | `vl_vector` 维度=2560，文本/图片一致 |
| E-DIM-02 | 维度严格校验拒脏写 | / 异常维度向量 | 入库 | 维度≠2560 直接拒绝，不脏写 |
| E-DIM-03 | 文本 query 直接召回图片块 | / D-IMG-VL（无文字图） | 文本 query | 经统一空间**可跨模态命中**图片 chunk（非仅靠 OCR 文本） |
| E-DIM-04 | 以图 query 召回相关文本块 | / 图文混合库 | by-image | 统一空间下可召回语义相关文本块 |
| E-DIM-05 | OCR 文本 + 统一向量双通道 | / 图含文字 | 文本 query | 既可经 OCR 文本命中，也可经统一向量命中，结果合理不重复 |
| E-DIM-06 | 混合库融合口径一致 | / 混合库 | hybrid | 文本/图召回在同一空间，融合排序口径一致可解释 |
| E-DIM-07 | 遗留 1024 列不参与检索 | / 老数据 | 检索 | 仅查 `vl_vector(2560)`，老 1024 列不影响 |
| E-DIM-08 | 切换 imageProcessingMode 重建 | / 改 mode 重建 | 重建 | 向量按新 mode（OCR 文本 / VL 视觉）正确重建 |
| E-DIM-09 | 顺序扫描大库性能 | / 万级 chunk | 检索 | 无 HNSW 下延迟仍在 SLA（vector 超时 8s 内） |
| E-DIM-10 | 跨模态排序可解释 | / 混合命中 | 检索 | 文本/图同空间打分，排序口径一致 |
| E-DIM-11 | 纯图库文本检索 | / 纯 VL 图库 | 文本 query | 统一空间可召回视觉相关图；无相关则友好空态 |
| E-DIM-12 | 多模态质量基线 | / 混合黄金集 | 检索 | 文本↔图、图↔图、图↔文 各路 recall 达标 |

---

# F. 端到端检索质量指标（70）

## F-RECALL 召回率（16）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| F-RECALL-01 | 黄金 query 命中期望 chunk | / 黄金集 | 检索 | recall@5 达标 |
| F-RECALL-02 | recall@1/3/5/10 曲线 | / 黄金集 | 多 k | 随 k 单调不降 |
| F-RECALL-03 | 唯一锚点句必召 | / D-TXT-1 锚点 | query 锚点 | 必中该 chunk |
| F-RECALL-04 | 跨文档召回 | / 多文档相关 | 检索 | 相关文档都被召回 |
| F-RECALL-05 | 长文档深处召回 | / D-TXT-LONG 末尾事实 | query | 末尾事实可召回 |
| F-RECALL-06 | 表格内事实召回 | / D-XLSX | query 单元值 | 命中行块 |
| F-RECALL-07 | OCR 文本召回 | / D-PDF-SCAN | query | 命中 |
| F-RECALL-08 | 多语言召回 | / 双语库 | 检索 | 跨语言召回达标 |
| F-RECALL-09 | 同义召回 | / 同义 query | 检索 | 召回同义 chunk |
| F-RECALL-10 | 稀有词召回 | / 稀有专名 | 检索 | 命中 |
| F-RECALL-11 | 清洗后召回提升 | / D-NOISE | 对比 | 清洗后 recall 不降 |
| F-RECALL-12 | 分块策略对召回影响 | / 多策略对比 | 检索 | 记录各策略 recall |
| F-RECALL-13 | 改写对召回提升 | / 开关改写 | 检索 | 改写后召回升 |
| F-RECALL-14 | 召回稳定性 | / 同 query | 多次 | recall 稳定 |
| F-RECALL-15 | 大库召回不退化 | / 万级 chunk | 检索 | 召回率维持 |
| F-RECALL-16 | 召回为空的合理性 | / 库外 query | 检索 | 应空则空，不强行命中 |

## F-PRECISION 准确率（14）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| F-PRECISION-01 | precision@5 达标 | / 黄金集 | 检索 | 前 5 相关占比达标 |
| F-PRECISION-02 | top1 高相关 | / 黄金集 | 检索 | top1 为最相关 |
| F-PRECISION-03 | 无关结果占比低 | / 检索 | 看前列 | 噪声/无关占比低 |
| F-PRECISION-04 | 精排提升 precision | / 开关精排 | 对比 | 精排后 precision 升 |
| F-PRECISION-05 | 权重调优 precision | / 调权 | 对比 | 合适权重 precision 最优 |
| F-PRECISION-06 | 去重提升有效结果占比 | / D-DUP | 检索 | 去重后有效结果更多 |
| F-PRECISION-07 | 负样本压制 | / CORP-NEG | 检索 | 干扰文档不进前列 |
| F-PRECISION-08 | 多义词 precision | / 多义 query | 检索 | 主义项相关靠前 |
| F-PRECISION-09 | 长 query precision | / 长 query | 检索 | 主旨相关靠前 |
| F-PRECISION-10 | 表格 precision | / 表格 query | 检索 | 命中正确行块 |
| F-PRECISION-11 | 多模态 precision | / 混合 | 检索 | 模态正确不错配 |
| F-PRECISION-12 | 跨语言 precision | / 双语 | 检索 | 相关性序合理 |
| F-PRECISION-13 | precision 稳定性 | / 同 query | 多次 | 稳定 |
| F-PRECISION-14 | 清洗对 precision | / D-NOISE | 对比 | 清洗后 precision 升 |

## F-RANK 排序质量 MRR/NDCG（16）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| F-RANK-01 | MRR 达标 | / 黄金集 | 检索 | 首个相关位次靠前 |
| F-RANK-02 | NDCG@10 达标 | / 分级相关黄金集 | 检索 | NDCG 达标 |
| F-RANK-03 | 高相关优先 | / 分级标注 | 检索 | 高相关排在中/低相关前 |
| F-RANK-04 | 精排改善排序指标 | / 开关精排 | 对比 | MRR/NDCG 升 |
| F-RANK-05 | 排序可复现 | / 同 query | 多次 | 序稳定 |
| F-RANK-06 | 同分稳定排序 | / 同分候选 | 检索 | 稳定 tie-break |
| F-RANK-07 | 权重对排序 | / 调权 | 对比 | 排序合理变化 |
| F-RANK-08 | 跨文档排序公平 | / 多文档 | 检索 | 不偏单一文档 |
| F-RANK-09 | 长短 chunk 排序公平 | / 长短混 | 检索 | 看相关性不看长度 |
| F-RANK-10 | 新旧文档排序 | / 含时间 | 检索 | 按相关性（或按设计时效） |
| F-RANK-11 | 去重后排序 | / D-DUP | 检索 | 去重不破坏相关序 |
| F-RANK-12 | 多模态排序 | / 混合 | 检索 | 模态间排序口径一致 |
| F-RANK-13 | 负样本排序靠后 | / CORP-NEG | 检索 | 干扰靠后 |
| F-RANK-14 | 分数与排序一致 | / 看分数 | 检索 | 展示分降序 |
| F-RANK-15 | 排序指标基线记录 | / 黄金集 | 检索 | 输出 MRR/NDCG 基线 |
| F-RANK-16 | 排序对 topK 截断鲁棒 | / topK 变化 | 检索 | 截断不破坏相对序 |

## F-NEG 负样本与幻觉防护（12）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| F-NEG-01 | 库外问题空结果 | / 库外 query | 检索 | 空/低分 + 友好空态 |
| F-NEG-02 | 干扰文档不误命中 | / CORP-NEG | 检索 | 不进前列 |
| F-NEG-03 | 反向语义 | / 相反含义 | 检索 | 不高分误召 |
| F-NEG-04 | 拼凑无意义 query | / 乱拼 | 检索 | 不强行命中 |
| F-NEG-05 | 低相似阈值保护 | / 弱相关 | 检索 | 低于阈值不返回（若设阈） |
| F-NEG-06 | 空库检索 | / 空 KB | 检索 | 友好空态，非报错 |
| F-NEG-07 | 单 chunk 库 | / 1 chunk | 检索 | 合理返回或空 |
| F-NEG-08 | 同名不同义不串 | / 跨库同词 | 检索 | 不串语义 |
| F-NEG-09 | 删除文档后不再召回 | / 删 chunk | 检索 | 立即不可召回 |
| F-NEG-10 | 停用文档不召回 | / 停用 | 检索 | 不召回 |
| F-NEG-11 | 噪声不冒充答案 | / D-NOISE | 检索 | 噪声不进前列 |
| F-NEG-12 | 负样本误命中即记 bug | / CORP-NEG | 检索 | 任何明显误召记录为质量 bug |

## F-DEDUP-CROSS 跨文档去重/一致性（12）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| F-DEDUP-CROSS-01 | 跨文档重复内容去重 | / 两文档同段 | 检索 | 结果不重复 |
| F-DEDUP-CROSS-02 | 同文档多版本 | / v1/v2 | 检索 | 按设计保留/去旧 |
| F-DEDUP-CROSS-03 | 近重复合并 | / 近重复 | 检索 | 合并展示 |
| F-DEDUP-CROSS-04 | 去重保留最佳来源 | / 多来源 | 检索 | 留最相关 |
| F-DEDUP-CROSS-05 | 去重不丢差异信息 | / 相似有别 | 检索 | 差异内容保留 |
| F-DEDUP-CROSS-06 | 大量重复性能 | / 海量重复 | 检索 | 不退化 |
| F-DEDUP-CROSS-07 | 去重跨组织隔离 | / A、B 同内容 | 检索 | 各自去重不串 |
| F-DEDUP-CROSS-08 | 去重对 recall 无害 | / D-DUP | 检索 | recall 不降 |
| F-DEDUP-CROSS-09 | 去重展示溯源 | / 合并结果 | 看结果 | 可展开多来源 |
| F-DEDUP-CROSS-10 | 去重稳定 | / 同 query | 多次 | 稳定 |
| F-DEDUP-CROSS-11 | 重分块后跨文档去重 | / 重分块 | 检索 | 仍去重 |
| F-DEDUP-CROSS-12 | 去重边界（恰好阈值） | / 边界相似 | 检索 | 判定一致可解释 |

---

# G. 组织 / 角色视角（50）

## G-ORG 组织隔离与质量一致（14）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| G-ORG-01 | 组织 A 仅检索 A 数据 | P-OWNER | 检索 | 仅本组织 + 公开库命中 |
| G-ORG-02 | A 不命中 B 私有内容 | P-OWNER | query B 独有事实 | 不命中 |
| G-ORG-03 | 越权 kbIds=B 库 | P-MEMBER | 构造 kbIds | 403 + 友好提示 |
| G-ORG-04 | 同 query 跨组织各召各的 | P-OWNER vs P-ORGB | 同 query | 各返回各自数据 |
| G-ORG-05 | 切组织检索结果切换 | P-SUPER-PLAT | A→B | 结果集随组织切换 |
| G-ORG-06 | 破玻璃全平台检索 | P-SUPER-PLAT | 全平台 | 跨组织聚合召回 |
| G-ORG-07 | 退破玻璃回组织口径 | P-SUPER-PLAT→组织 | 切回 | 仅本组织 |
| G-ORG-08 | 组织隔离不降质 | P-OWNER | 黄金集 | A 内 recall/precision 与全量基线一致 |
| G-ORG-09 | 无组织上下文不泄漏 | 无 X-Org-Id | 检索 | 仅公开/空，不返回私有 |
| G-ORG-10 | 检索日志归属正确 | P-OWNER | 检索后查日志 | org/KB 归属正确 |
| G-ORG-11 | 个人组织检索独立 | P-PERSONAL | 检索 | 仅个人 + 公开 |
| G-ORG-12 | 跨组织同名库不混 | / 同名库 | 检索 | 按组织隔离 |
| G-ORG-13 | 组织删除后不可检索 | / 删组织库 | 检索 | 不可召回 |
| G-ORG-14 | 越权友好报错 | P-MEMBER | 越权检索 | 友好"无权访问该知识库" |

## G-PUBLIC 公开库（12）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| G-PUBLIC-01 | 公开库被本组织检索 | P-OWNER | 检索含公开库 | 命中 |
| G-PUBLIC-02 | 公开库被他组织检索 | P-ORGB | 检索公开库 | 命中（公开可读） |
| G-PUBLIC-03 | 公开库质量一致 | 多组织 | 检索公开库黄金 | 各组织召回一致 |
| G-PUBLIC-04 | 公开转私有后失效 | / 收紧后 | P-ORGB 检索 | 不再命中 |
| G-PUBLIC-05 | 私有转公开后生效 | / 放开后 | P-ORGB 检索 | 可命中 |
| G-PUBLIC-06 | 公开库去重跨组织 | / 公开重复 | 检索 | 各组织独立去重 |
| G-PUBLIC-07 | 公开库不计入资产但可检索 | P-OWNER | 检索 vs 资产 | 检索可命中，资产不计（口径差异） |
| G-PUBLIC-08 | 公开库多模态检索 | / 公开图文 | 检索 | 跨组织可检索 |
| G-PUBLIC-09 | 公开库越权写拒绝 | P-ORGB | 试写公开库 | 403（读不可写） |
| G-PUBLIC-10 | 公开库排序一致 | 多组织 | 检索 | 排序口径一致 |
| G-PUBLIC-11 | 公开 + 私有混合检索 | P-OWNER | 检索 | 私有优先级/混排合理 |
| G-PUBLIC-12 | 公开库 API key 检索 | P-APIKEY-A | 检索 | 命中公开库 |

## G-APIKEY API key 检索（12）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| G-APIKEY-01 | X-API-Key 调 /search | P-APIKEY-A | 带 X-API-Key | 200，召回本组织+公开 |
| G-APIKEY-02 | Bearer 调 /search | / | Authorization: Bearer | 401 + 友好提示用 X-API-Key |
| G-APIKEY-03 | key 自动绑定组织 | P-APIKEY-A | 检索 | 仅返回 key 绑定组织数据 |
| G-APIKEY-04 | key 检索质量同会话 | P-APIKEY-A vs P-OWNER | 同 query | 结果一致 |
| G-APIKEY-05 | key allowedKbIds 限定 | / 限定库 key | 检索 | 仅限定库命中 |
| G-APIKEY-06 | 越权库 key | / key 不含某库 | 检索该库 | 不命中/拒绝 |
| G-APIKEY-07 | 停用 key 检索 | / 停用 | 检索 | 401 + 友好 |
| G-APIKEY-08 | 限流 429 | / 高频 | 检索 | 429 + 友好"调用过于频繁" |
| G-APIKEY-09 | key 检索计量归属 | P-APIKEY-A | 检索后 | 成本计入该组织 |
| G-APIKEY-10 | key 检索日志归属 | P-APIKEY-A | 日志 | org/KB 正确 |
| G-APIKEY-11 | key /answer 应答 | P-APIKEY-A | /answer | 本组织数据应答 |
| G-APIKEY-12 | key 不泄漏他组织 | P-APIKEY-A | query 他组织事实 | 不命中 |

## G-ROLE 角色一致性（12）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| G-ROLE-01 | OWNER/ADMIN/MEMBER 同库检索一致 | 三角色 | 同 query 同库 | 召回/排序一致 |
| G-ROLE-02 | MEMBER 仅 ORG 可见库 | P-MEMBER | 检索 | PRIVATE 库不可检索 |
| G-ROLE-03 | 管理员可检索 PRIVATE 库 | P-OWNER | 检索 PRIVATE | 命中 |
| G-ROLE-04 | 角色变更后检索范围变 | / 降级 MEMBER | 检索 | 范围随之收紧 |
| G-ROLE-05 | KB_VIEWER 只读检索 | / viewer | 检索 | 可检索不可写 |
| G-ROLE-06 | 角色越权写拒绝 | P-MEMBER | 试写 | 403 友好 |
| G-ROLE-07 | 角色对质量无影响 | 多角色 | 黄金集 | 有权范围内质量一致 |
| G-ROLE-08 | 平台超管全可见 | P-SUPER-PLAT | 检索 | 全平台可检索 |
| G-ROLE-09 | 角色 × 公开库 | P-MEMBER | 检索公开库 | 可命中 |
| G-ROLE-10 | 角色切组织一致 | P-SUPER-PLAT | 切组织 | 各组织内角色口径一致 |
| G-ROLE-11 | 无权页面/接口友好 | P-MEMBER | 越权接口 | 403 友好，不裸码 |
| G-ROLE-12 | 角色检索日志含主体 | 多角色 | 日志 | 记录操作主体 |

---

# H. 鲁棒性与友好报错（45）

## H-INPUT 输入边界（15）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| H-INPUT-01 | 空 query | P-OWNER | 提交空 | 友好"请输入检索查询"，非 500 |
| H-INPUT-02 | 纯空格 query | / "   " | 提交 | 友好拦截 |
| H-INPUT-03 | 超长 query（万字） | / 超长 | 提交 | 截断/友好提示，不超时崩 |
| H-INPUT-04 | 特殊字符 query | / "<>{}\\" | 提交 | 安全处理，不报错 |
| H-INPUT-05 | SQL/ES 注入式 | / 注入串 | 提交 | 无注入，安全 |
| H-INPUT-06 | 提示词注入 | / "忽略上文…" | 提交 | 不被劫持 |
| H-INPUT-07 | Emoji/Unicode query | / emoji | 提交 | 正常处理 |
| H-INPUT-08 | 多行 query | / 含换行 | 提交 | 正常处理 |
| H-INPUT-09 | 非法 kbIds | / kbIds=abc | 提交 | 友好"请输入有效知识库 ID" |
| H-INPUT-10 | 不存在 kbIds | / kbIds=999999 | 提交 | 友好提示，非 500 |
| H-INPUT-11 | 非法 strategy | / strategy=foo | 提交 | 友好/回退默认 |
| H-INPUT-12 | 非法 topK/weight | / 越界 | 提交 | 友好/钳制 |
| H-INPUT-13 | 缺必填字段 | / 无 query 字段 | 提交 | 友好校验 |
| H-INPUT-14 | 超大 payload | / 超大 body | 提交 | 413/友好，非 500 |
| H-INPUT-15 | 错误 Content-Type | / 非 JSON | 提交 | 415/友好，非 500 |

## H-FAIL 下游失败与降级（16）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| H-FAIL-01 | 改写 LLM 超时 | / 慢 | 检索 | 降级原 query + 友好提示 |
| H-FAIL-02 | embedding 服务失败 | / 故障 | 检索 | 友好"服务暂时异常"，非裸 500 |
| H-FAIL-03 | ES 不可用 | / ES down | 检索 | 降级向量单路 + 友好提示 |
| H-FAIL-04 | pgvector 不可用 | / PG 向量故障 | 检索 | 降级 BM25 单路 + 友好 |
| H-FAIL-05 | rerank 超时 | / 慢 | 检索 | 降级融合序 + 友好 |
| H-FAIL-06 | rerank 服务 5xx | / 故障 | 检索 | 友好降级 |
| H-FAIL-07 | 模型限流 429 | / 限流 | 检索 | 友好"调用过于频繁，请稍后" |
| H-FAIL-08 | 余额/配额不足 | / 配额满 | 检索 | 友好提示，非裸码 |
| H-FAIL-09 | OCR 服务失败 | / 故障 | 入库 | 友好标失败可重试 |
| H-FAIL-10 | VL 服务失败 | / 故障 | 入库 | 友好降级 |
| H-FAIL-11 | 全链路超时 | / 整体慢 | 检索 | 超时友好提示，不挂死 |
| H-FAIL-12 | 部分库故障 | / 一库异常 | 多库检索 | 其余库正常 + 提示部分失败 |
| H-FAIL-13 | 数据库连接异常 | / DB 抖动 | 检索 | 友好"服务暂时异常" |
| H-FAIL-14 | 错误不暴露内部 | / 任意错误 | 看提示 | 不含堆栈/SQL/内部类名 |
| H-FAIL-15 | 错误带 traceId 便于排查 | / 错误 | 响应 | 含 traceId，但文案友好 |
| H-FAIL-16 | 降级结果有标注 | / 降级 | 看结果 | 提示"已降级检索"，用户可知 |

## H-CONCURRENCY 并发与性能（14）

| 编号 | 用例名称 | 角色/数据 | 步骤要点 | 预期结果 |
|---|---|---|---|---|
| H-CONCURRENCY-01 | 并发检索互不串 | P-OWNER | 并发不同 query | 各自正确结果 |
| H-CONCURRENCY-02 | 并发跨组织隔离 | A、B 并发 | 检索 | 不串数据 |
| H-CONCURRENCY-03 | 快速连发同 query | / 连发 | 检索 | 结果一致，无竞态 |
| H-CONCURRENCY-04 | 大库检索延迟 | / 万级 chunk | 检索 | 延迟在 SLA 内 |
| H-CONCURRENCY-05 | 大 topK 性能 | / topK 大 | 检索 | 不 OOM/超时 |
| H-CONCURRENCY-06 | 并发入库 + 检索 | / 边入边查 | 检索 | 已完成部分可检索，不脏读 |
| H-CONCURRENCY-07 | 切组织竞态 | P-SUPER-PLAT | 连切检索 | 最终为目标组织结果 |
| H-CONCURRENCY-08 | 重复请求幂等 | / 同请求 | 重发 | 不产生副作用 |
| H-CONCURRENCY-09 | 高并发限流保护 | / 压测 | 检索 | 触发限流友好提示，不雪崩 |
| H-CONCURRENCY-10 | 长尾延迟 p95 | / 压测 | 统计 | p95 达标 |
| H-CONCURRENCY-11 | 缓存命中一致 | / 同 query | 连发 | 缓存结果一致 |
| H-CONCURRENCY-12 | 资源释放无泄漏 | / 长跑 | 压测 | 无连接/内存泄漏 |
| H-CONCURRENCY-13 | 取消/中断请求 | / 中断 | 检索 | 优雅取消，不残留 |
| H-CONCURRENCY-14 | 并发错误仍友好 | / 并发触发错误 | 看提示 | 错误提示仍友好 |

---

## 附：维护与执行约定

- **指标基线**：F 区（recall@k / precision@k / MRR / NDCG）需先用黄金集跑出基线，后续作回归阈值。
- **造数据原则**：每篇合成文档植入唯一锚点句 + 黄金 query→期望 chunk 映射，断言可量化。
- **友好报错为一等公民**：任何维度的失败分支都要断言"提示友好、无裸码/堆栈/500"；不友好即记 bug。
- **多视角强制**：召回/隔离类用例尽量「切到另一组织/角色验证生效或失效」。
- **编号前缀**：A 分块 / B 检索策略 / C 改写 / D 清洗 / E 多模态 / F 端到端 / G 组织角色 / H 鲁棒与友好报错。
- 合计 **500** 条（A70 / B80 / C40 / D55 / E90 / F70 / G50 / H45）。
