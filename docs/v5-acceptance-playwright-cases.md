# RAGForge V5/V6 验收测试用例（Playwright 有头模式，在云服务器上面测试，不是在本地测试）

> 编制：2026-06-22 · 修订：2026-06-24 · 测试架构师：@guandezhi
>
> 目标：为 T8 数据清洗、T9 多策略分块、T10 多模态一期、T11 Answer-as-LLM、**Q 质量看板**、**A 应答 Playground** 各设计 10 条 Playwright e2e 用例，覆盖黄金路径 + 边界值 + 异常注入 + 性能基线 + 跨场景组合，**全部用 headed 模式（`--headed`）肉眼可见验证**。
>
> 用法：找到对应任务章节 → **复制 `=== COPY START ===` 到 `=== COPY END ===` 之间所有内容** → 粘贴给 Codex 单独执行一个任务。每个任务交付一个独立 PR，PR 之间不能并行。
>
> 全局约定：
> - 测试目录：`frontend/tests/e2e/v5-acceptance/<task>/`（独立子目录，不污染 `t10-rewrite/`）
> - 命名规范：`<task>-acc-<NN>-<short-name>.spec.ts`（NN=01..10）
> - 测试运行命令：`npx playwright test tests/e2e/v5-acceptance/<task> --headed --workers=1`
> - 共享 helpers：`frontend/tests/e2e/v5-acceptance/_helpers/` 下放公共 fixture loader、KB 创建、登录、文档上传、轮询 parse_status
> - 每个用例必须独立创建 KB（命名 `acc-<task>-<NN>-<timestamp>`）+ 清理（afterAll 删除 KB）
> - 必须存档 trace.zip 到 `frontend/test-results/v5-acceptance/<task>/`
> - 验收门槛：每个任务 10 条全绿才算 PASS，任何一条 FAIL 都不允许合并

---

## V6 之后的回归注意事项（写新用例 / 重跑旧用例前必读）

V6 之后基础设施有以下变化，影响测试 helper 写法和断言口径：

1. **文件上传通道改了**：默认走前端直传 OSS（presign + register），原来 `POST /api/v1/documents` relay 通道仍保留但实际不会被前端调用。**Test helper 可以继续打 `POST /api/v1/documents` 后端 relay** —— 那条路径 backend 仍然完整支持，跑得稳。如果要走 UI 上传路径（hit drop-zone），需要等到 OSS CORS 在测试环境配好。
2. **文件 hash 算法**：前端用 Web Crypto API 算 SHA-256（不是 MD5）。后端 identity 三层匹配仍然支持 contentMd5 字段（字段名没改，但内容是 SHA-256）。
3. **支持纯文本 `.txt`**：之前 prompt 里写"保守不写文本"是误判，后端 DocumentPipelineService 的 `text/*` 分支直接 readRawText，**任何 text/* MIME 都能解析**。t8/t9 fixture 可以放心用 `.txt`。
4. **KB.answer_mode 默认 OFF**（V30 已经把 KB 编辑 UI 暴露了，但 SQL 默认值还是 OFF，V31 待办）：**所有 t11/A 系列用例必须在创建 KB 后立刻 SQL/Admin API 把 answer_mode 改成 ON**，否则 /answer 直接 403 ANSWER_DISABLED。
5. **answer_model 默认 qwen-plus**（V29），不再是 qwen-max。t11-acc-07 验收口径正确。
6. **AnswerService PII 处理**：SSE retrieval event 的 chunk content 通过 piiMaskCleaner.mask() 脱敏推送，前端拿到的 chunks 已经是脱敏后的；GuardRails 已移除 OUT_OF_SCOPE，只剩 NO_CITATIONS / PII_LEAK。
7. **质量看板和应答 Playground 都需要 LLM-as-Judge**（DeepSeek-V4-Flash 接入完成）：质量看板的 seed 数据可以**绕过真实 DeepSeek**直接 SQL 写 judge_results 表，应答 Playground 必须真调 DashScope（qwen-plus）。
8. **测试账号**（auth-gateway 必须含）：
   - `admin` / `admin` → ragRole=ADMIN
   - `qa_kbeditor` / `qa_kbeditor` → ragRole=KB_EDITOR（用例内自己授权特定 KB）
   - `qa_viewer` / `qa_viewer` → ragRole=VIEWER（无任何 KB 权限）
   prod profile 必须**不存在**这 3 个账号（启动时 fail-fast 检查）。

## 用户能看懂的错误提示原则（贯穿所有用例）

每条用例只要包含"失败路径"，**断言一定要校验 UI 文案，不能仅校验 HTTP 状态码**。这是验收测试和单元测试最大区别。

错误文案必须遵守：

| 不能出现 | 应该出现 |
|---|---|
| `traceId` / `xxxxxxxx-xxxx-xxxx` | 不出现任何技术 id |
| Java stack trace 关键词（`at com.ragforge.`）| 不出现 |
| 后端 error code（`KB_WRITE_FORBIDDEN`、`SAMPLE_RATE_TOO_HIGH_REQUIRES_CONFIRM`）| 直接显示翻译后的中文 |
| `500 Internal Server Error` 裸字 | "服务暂时异常，请稍后重试" |
| `BizException` | 不出现 |
| 英文异常 message（`unexpected end-of-input`） | 中文化 |
| 字段为 null / undefined 显示 `NaN` 或空白 | "暂无数据" / "—" / 灰色占位 |

每条用例**至少 1 处**这种 UI 文案断言，证明用户体验真正可用。

---

## 任务 T8：数据清洗验收（10 个用例）

`=== COPY START ===`

```
角色：你是 RAGForge 项目的 Cursor 执行者，跟 @guandezhi 架构师协作。本任务执行 T8 数据清洗的
端到端 Playwright 验收测试，必须 headed 模式跑给架构师看。

任务：T8 数据清洗 Playwright e2e 验收（10 个用例）
依赖：T8 已合并（commit fcd10f6 + 1a3ecb5）、本地 backend / frontend / PostgreSQL / Elasticsearch / DashScope 全部可用
工期：1-1.5 天

必读上下文：
1. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/pipeline/cleaner/
   - CleanProfile.java（l1/l2/l3/l4Enabled、piiPolicy、skipClean）
   - L1NormalizeCleaner.java、L2DenoiseCleaner.java、L3PiiMaskCleaner.java
   - PiiPatterns.java（PHONE / ID_CARD / EMAIL / BANK_CARD 四种正则）
2. /Users/amy/CursorProject/rag-forge/backend/src/main/resources/db/migration/V23__clean_profiles.sql
   - clean_profiles 表（scope='KB'|'TENANT' + scope_id + JSONB config）
   - documents.clean_profile_id + clean_report_json
3. /Users/amy/CursorProject/rag-forge/backend/src/main/java/com/ragforge/controller/AdminE2eController.java
   - GET /api/v1/admin/e2e/chunks/{docId}/raw 拿真实 chunk content
4. /Users/amy/CursorProject/rag-forge/frontend/src/views/KnowledgeBase.vue
   - 看 KB 创建/编辑面板有没有 clean profile 入口；如果没有，本任务允许直接走 SQL 写 clean_profiles 表 + 上 admin API 配置

测试目录：frontend/tests/e2e/v5-acceptance/t8/
测试运行：npx playwright test tests/e2e/v5-acceptance/t8 --headed --workers=1
trace 归档：frontend/test-results/v5-acceptance/t8/

== fixture 准备 ==
在 frontend/tests/e2e/v5-acceptance/t8/fixtures/ 下准备：
1. clean-noisy-header-footer.txt：100 行，每页前 3 行重复"广州市某科技公司 第 N 页"+ "保密 内部资料" 水印
   （手工构造，模拟 PDF 转纯文本后的页眉页脚噪声）
2. clean-pii-zh.txt：含 5 种 PII 各 2 处：手机号 138xxxx5678、身份证号 440103199001011234、
   邮箱 alice@example.com、银行卡 6222 0202 0001 2345 6789、地址（不在 PiiPatterns 覆盖范围，故意放进去验证）
3. clean-unicode-zerowidth.txt：含 NBSP（U+00A0）、零宽空格（U+200B）、全角空格（U+3000）混合，
   且含 "ｆｕｌｌｗｉｄｔｈ" 全角英文
4. clean-toc-watermark.pdf：用 reportlab 或手动构造一份 5 页 PDF，含目录页 + 每页底部水印
5. clean-mixed-everything.txt：上面 1+2+3 的混合
6. clean-pure-content.txt：完全干净的 200 字纯文本（baseline）
7. clean-empty-after-strip.txt：内容全部由页眉/水印/PII 组成，清洗后接近空文本

== 10 个用例设计 ==

【t8-acc-01-l1-normalize-whitespace.spec.ts】
目标：L1 规范化必须把 NBSP / 零宽 / 全角空格 / 全角英文统一为半角
步骤：
  1. 创建 KB（默认 clean profile：l1=true, l2=true, l3=true, piiPolicy=MASK）
  2. 上传 clean-unicode-zerowidth.txt
  3. 轮询直到 parse_status=COMPLETED
  4. GET /api/v1/admin/e2e/chunks/{docId}/raw 拿 chunk content
断言：
  - chunk content 不含 U+00A0、U+200B、U+3000、U+FEFF
  - "ｆｕｌｌｗｉｄｔｈ" 已被转换为 "fullwidth"（如果 L1 实现了全角转半角；如未实现，期望保留并写明）
  - 连续空格压缩为单个空格
  - documents.clean_report_json.l1.normalizedChars > 0
失败处理：截图当前 chunk 列表 UI + 后端 raw JSON 全部存 trace

【t8-acc-02-l2-denoise-header-footer.spec.ts】
目标：L2 必须识别并删除每页重复的页眉页脚
步骤：
  1. KB（clean profile：l1=true, l2=true, l3=false）
  2. 上传 clean-noisy-header-footer.txt
  3. 轮询完成
  4. 拉 chunks
断言：
  - 没有任何 chunk 的 content 同时含 "广州市某科技公司" + "第 N 页" 这种组合
  - documents.clean_report_json.l2.removedRegions 数量 ≥ 文件实际页数 × 3（页眉行）
  - 各 RemovedRegion 包含 reason 字段（如 REPEATED_EDGE_LINE）

【t8-acc-03-l2-denoise-watermark-toc.spec.ts】
目标：L2 必须识别水印行 + 目录行（TOC pattern）
步骤：
  1. KB（l2=true）
  2. 上传 clean-toc-watermark.pdf
  3. 等完成
断言：
  - 没有 chunk content 含 "保密 内部资料" 水印
  - 没有 chunk 是纯目录行（如 "1.1 章节标题 ............ 3"）
  - clean_report_json.l2 列出 TOC_LINE / WATERMARK 类型的 RemovedRegion

【t8-acc-04-l3-pii-mask-zh.spec.ts】
目标：L3 piiPolicy=MASK 必须用 ***/打码替换 5 种 PII
步骤：
  1. KB（l3=true, piiPolicy=MASK）
  2. 上传 clean-pii-zh.txt
  3. 等完成
断言：
  - chunk content 不含原始手机号、身份证号、邮箱、银行卡（regex 验证）
  - 含掩码字符（如 138****5678 或 [PHONE_MASKED]，按 L3PiiMaskCleaner 实际实现校验）
  - clean_report_json.l3.maskedCount ≥ 8（5 种 × 至少 2 处中至少识别到 4 种）

【t8-acc-05-l3-pii-policy-hash.spec.ts】
目标：piiPolicy=HASH 时同样的 PII 内容必须替换为 HASH 形式且同输入 → 同 HASH（确定性）
步骤：
  1. KB-A 和 KB-B 各自 clean profile：l3=true, piiPolicy=HASH
  2. 上传 clean-pii-zh.txt 到两个 KB
  3. 拉两组 chunks
断言：
  - chunk content 不含原始 PII
  - 同一原始号码（如手机号 13812345678）在 KB-A 和 KB-B 产出的 hash 字符串必须一致
  - hash 长度固定（按 L3PiiMaskCleaner 实现，预期 SHA-256 16 位）

【t8-acc-06-l3-pii-policy-reject.spec.ts】
目标：piiPolicy=REJECT 时含 PII 文档必须整体 parse_status=FAILED 且 error_msg 含 PII_REJECTED
步骤：
  1. KB clean profile：l3=true, piiPolicy=REJECT
  2. 上传 clean-pii-zh.txt
  3. 等到 parse_status 终态
断言：
  - parse_status=FAILED
  - error_msg 含 "PII_REJECTED" 或类似关键字
  - documents.chunk_count = 0

【t8-acc-07-skip-clean-bypass.spec.ts】
目标：skipClean=true 时清洗管道整条跳过，所有 PII 和噪声原样进入 chunks
步骤：
  1. KB clean profile：skipClean=true
  2. 上传 clean-mixed-everything.txt
  3. 等完成
断言：
  - chunk content 仍含原始 PII（手机号 / 邮箱）
  - chunk content 仍含页眉重复行
  - clean_report_json.skipped=true 或 clean_report_json 为空对象
  - 全文长度 ≈ 原文长度（容忍 ±5%）

【t8-acc-08-l1-l2-l3-pipeline-order.spec.ts】
目标：L1→L2→L3 顺序正确（L1 规范化先，L3 在已去噪后的文本上脱敏）
步骤：
  1. KB clean profile：l1=l2=l3=true, piiPolicy=MASK
  2. 上传 clean-mixed-everything.txt
  3. 等完成
断言：
  - chunk 总数 < 不清洗时的 chunk 总数（噪声被压缩）
  - 没有 PII 残留
  - 没有页眉/水印残留
  - clean_report_json 同时含 l1/l2/l3 三段记录
  - clean_report_json.l3.maskedCount 与 clean_report_json.l1.normalizedChars 数值合理（脱敏发生在规范化之后，统计基数应为 L2 输出后的字数）

【t8-acc-09-empty-after-strip-fallback.spec.ts】
目标：清洗后内容接近空时必须 fail-fast 而不是产生 0 chunk 的"成功"假象
步骤：
  1. KB clean profile：l1=l2=l3=true
  2. 上传 clean-empty-after-strip.txt
  3. 等到终态
断言：
  - parse_status=FAILED，error_msg 含 "EMPTY_AFTER_CLEAN" 或类似 token
  - 或者 parse_status=COMPLETED 但 chunk_count > 0 且 documents.warnings 字段含告警（取决于实现，但**两者必须二选一明确**，不允许"COMPLETED + 0 chunks + 无告警"）

【t8-acc-10-perf-baseline-200kb-doc.spec.ts】
目标：清洗管道在 200KB 中文 + PII 混合文档上不阻塞主线程，端到端 ≤ 10 秒
步骤：
  1. fixture 拼一个 200KB 的 clean-perf-200kb.txt（重复 clean-mixed-everything.txt 直到 200KB）
  2. 记录上传开始 → parse_status=COMPLETED 时间戳
  3. 同时启一个浏览器 tab 在 KnowledgeBase 列表页持续点击翻页，验证不卡顿
断言：
  - 端到端耗时 ≤ 10s（基线，超过则警告但不强制 FAIL）
  - 同时翻页操作的 UI 响应延迟 < 500ms
  - documents.clean_report_json 存在且非空

== 禁止项 ==
- 不能改任何 cleaner 业务代码（只读 + 测试）
- 不能跳过 trace 归档
- 不能跑 --workers=多线程（fullyParallel=false 已定）
- 不能用 mock 后端，必须真实 DashScope + 真实 PG + 真实 ES
- 不能让用例之间共享 KB（每条独立创建独立销毁）

== 验收门槛 ==
1. 10 条全绿，trace.zip 全部归档
2. 截图证据贴 PR：每条用例至少 2 张（一张是上传完成 UI、一张是 chunks 列表 UI）
3. 后端 /actuator/prometheus 在测试运行后必须看到 ragforge.ingest.created 计数 = 10
4. 任何一条 FAIL 必须附问题分析 + 是 cleaner 真 bug 还是测试设计错（决定 PR 是否需要附 hotfix）
5. PR 标题：test(v5/T8): playwright headed acceptance suite (10 cases)
6. 完成后在 docs/v5-execution-tasks.md 末尾追加：- T8 验收 ✅ <commit-sha> 2026-MM-DD（10/10 PASS）

== 执行流程 ==
1. 先输出"我将创建的关键文件清单"（≤15 个文件，含 fixture + spec + helpers）
2. 提交实施计划 + 不确定项（如 PII 掩码具体字符），等架构师确认
3. 准备 fixture（用 Python/JS 脚本生成，脚本本身 commit 进 fixtures/_generators/）
4. 写 spec 文件，每条独立
5. 跑 --headed 看一遍，截屏存 trace
6. 全绿后 PR
```

`=== COPY END ===`

---

## 任务 T9：多策略分块验收（10 个用例）

`=== COPY START ===`

```
角色：你是 RAGForge 项目的 Cursor 执行者，跟 @guandezhi 架构师协作。本任务执行 T9 多策略分块的
端到端 Playwright 验收测试，headed 模式。

任务：T9 多策略分块 Playwright e2e 验收（10 个用例）
依赖：T9 已合并（commit 02e34ed + 1a3ecb5）；T8 验收已 PASS（chunker 上游依赖 cleaner 真实管道）
工期：1.5-2 天

必读上下文：
1. backend/src/main/java/com/ragforge/pipeline/chunker/
   - ChunkerProfile.java（defaultStrategy='MARKDOWN_HEADING', fallbackChain=[RECURSIVE, FIXED_WINDOW]）
   - FixedWindowChunkerStrategy / MarkdownHeadingChunkerStrategy / RecursiveChunkerStrategy /
     SemanticChunkerStrategy / TableAwareChunkerStrategy
   - ChunkParams.java（maxTokens / overlap / minChunkChars 等）
2. backend/src/main/resources/db/migration/V24__chunker_profiles.sql
   - document_chunks.chunker_strategy / chunker_params_json / heading_path
   - knowledge_bases.chunker_profile_json
3. backend/src/main/java/com/ragforge/controller/ChunkerAbController.java
   - POST /api/v1/evaluation/chunker-ab（A/B lab）
4. frontend/src/views/EvaluationLab.vue
   - chunkerStrategyOptions 数组，看现有 UI 入口
5. backend/src/main/java/com/ragforge/controller/DocumentController.java
   - POST /api/v1/documents/{id}/rechunk

测试目录：frontend/tests/e2e/v5-acceptance/t9/
测试运行：npx playwright test tests/e2e/v5-acceptance/t9 --headed --workers=1
trace 归档：frontend/test-results/v5-acceptance/t9/

== fixture 准备 ==
1. chunk-markdown-headings.md：3 级 heading 嵌套（# / ## / ###），每个 section 200-500 字
2. chunk-no-headings-plain.txt：1500 字纯散文，无任何标点结构
3. chunk-mixed-table.md：含 1 个 5×4 的 Markdown 表格 + 周围段落文字
4. chunk-extra-long-paragraph.txt：单段 5000 字超长，无段落分隔
5. chunk-short-fragments.txt：50 段，每段 20-50 字
6. chunk-code-blocks.md：含 3 个 ```python 代码块，每个 30 行
7. chunk-semantic-topic-shift.txt：3 个明显话题段（科技/医疗/教育，各 600 字）
8. chunk-deeply-nested-list.md：5 级嵌套列表
9. chunk-bilingual-cn-en.txt：中英文混合，每段交替
10. chunk-large-table-only.md：单个 30 行 × 8 列表格

== 10 个用例设计 ==

【t9-acc-01-default-markdown-heading.spec.ts】
目标：默认策略 MARKDOWN_HEADING 在有 heading 的文档上必须按标题切，heading_path 字段正确填充
步骤：
  1. KB 默认 chunker_profile（defaultStrategy=MARKDOWN_HEADING）
  2. 上传 chunk-markdown-headings.md
  3. 等完成
断言：
  - 每个 chunk 的 chunker_strategy = 'MARKDOWN_HEADING'
  - 每个 chunk 的 heading_path 非空（如 "Intro > Setup > Install"）
  - chunk 总数 ≈ heading 数量（容忍 ±20%）
  - 同一 ## 下的内容不被跨切

【t9-acc-02-fixed-window-fallback-noheading.spec.ts】
目标：MARKDOWN_HEADING 在无 heading 文档上必须 fallback 到 RECURSIVE → FIXED_WINDOW
步骤：
  1. KB 默认 profile（fallbackChain=[RECURSIVE, FIXED_WINDOW]）
  2. 上传 chunk-no-headings-plain.txt
  3. 等完成
断言：
  - 没有 chunk 的 chunker_strategy = 'MARKDOWN_HEADING'
  - 至少存在 chunker_strategy ∈ {'RECURSIVE', 'FIXED_WINDOW'} 的 chunk
  - chunk 平均长度 ≈ chunker_params.maxTokens × 4（中文 char ≈ token × 1.5～2）

【t9-acc-03-table-aware-strategy.spec.ts】
目标：TABLE_AWARE 策略下表格必须作为完整 chunk 保留，不被中间切开
步骤：
  1. KB chunker_profile（defaultStrategy=TABLE_AWARE）
  2. 上传 chunk-mixed-table.md
  3. 等完成
断言：
  - 至少 1 个 chunk 的 chunker_params_json.isTable=true 或 heading_path 含表格标记
  - 表格 chunk 的 content 含完整的 5 行（包括 header + 4 行 data row）
  - 表格 chunk 不被切成多段（chunk 边界不落在 | 分隔符内）

【t9-acc-04-semantic-topic-shift.spec.ts】
目标：SEMANTIC 策略下 3 个明显话题段落必须各成 chunk（容忍 ±1）
步骤：
  1. KB chunker_profile（defaultStrategy=SEMANTIC）
  2. 上传 chunk-semantic-topic-shift.txt（科技/医疗/教育）
  3. 等完成
断言：
  - chunk 数量 ∈ [2, 5]（理想 3，容忍误差）
  - 没有任何 chunk 同时含 "人工智能" + "心脏病" + "教育部"（即话题没混在一个 chunk 里）
  - chunker_strategy = 'SEMANTIC'

【t9-acc-05-recursive-extra-long.spec.ts】
目标：RECURSIVE 策略下超长段落必须递归切到 maxTokens 以内
步骤：
  1. KB chunker_profile（defaultStrategy=RECURSIVE, params.maxTokens=512）
  2. 上传 chunk-extra-long-paragraph.txt（5000 字单段）
  3. 等完成
断言：
  - 每个 chunk 的 tokenCount ≤ 512 × 1.1（容忍 10%）
  - chunk overlap 字段验证有 overlap（前后 chunk 末/首字符有重叠，长度 ≈ params.overlap）
  - chunker_strategy 全部 = 'RECURSIVE'

【t9-acc-06-rechunk-strategy-change.spec.ts】
目标：POST /documents/{id}/rechunk 切换策略，旧 chunks 全删 + 新策略生效
步骤：
  1. 上传 chunk-markdown-headings.md，初始策略 MARKDOWN_HEADING
  2. 完成后记录 chunk 总数 N_old
  3. POST rechunk，body {strategy: 'FIXED_WINDOW', maxTokens: 256}
  4. 等 parse_status=COMPLETED
断言：
  - 老 chunk_id 不再存在
  - 新 chunks 全部 chunker_strategy = 'FIXED_WINDOW'
  - 新 chunk 总数 N_new > N_old（FIXED_WINDOW 比 HEADING 更碎）
  - documents.chunk_count = N_new

【t9-acc-07-chunker-ab-lab-ui.spec.ts】
目标：EvaluationLab 页 chunker-ab UI 必须能选多个策略 + 显示对比指标（chunk 数、平均长度、覆盖度）
步骤：
  1. 上传 chunk-mixed-table.md 到 KB
  2. 浏览器打开 /evaluation 页
  3. 选 4 个策略：MARKDOWN_HEADING / FIXED_WINDOW / SEMANTIC / TABLE_AWARE
  4. 选刚上传的 document，点击 "Run AB"
  5. 等结果渲染
断言：
  - UI 显示 4 列结果，每列含 chunkCount / avgLength / coverage
  - TABLE_AWARE 的表格 coverage 字段最高
  - 没有 column 报错（如 "strategy unsupported"）
  - 后台 /api/v1/evaluation/chunker-ab 200 OK

【t9-acc-08-short-fragments-merge.spec.ts】
目标：碎片化输入下，chunker 必须合并连续短段避免 chunk 爆炸
步骤：
  1. KB（默认策略，minChunkChars=100）
  2. 上传 chunk-short-fragments.txt（50 段 × 20-50 字 = 总 ~1500 字）
  3. 等完成
断言：
  - chunk 总数 ≤ 20（远低于 50 段）
  - 没有 chunk 长度 < minChunkChars（除非是文档末尾的 dangling）

【t9-acc-09-code-block-preserve.spec.ts】
目标：MARKDOWN_HEADING / RECURSIVE 策略必须保留 ```code``` 完整性，不切到 fence 中间
步骤：
  1. 上传 chunk-code-blocks.md
  2. 等完成
断言：
  - 任何 chunk 内的 ``` 数量必须是偶数（成对出现）
  - chunker_params_json 含 codeBlockPreserved=true 或类似标记
  - python 代码片段（如 "def main():") 完整在单一 chunk 内

【t9-acc-10-perf-baseline-multistrategy.spec.ts】
目标：5 种策略各跑一次，相对耗时合理且都能完成
步骤：
  1. 对同一份 chunk-markdown-headings.md（约 2000 字），分别上传到 5 个独立 KB
  2. 每个 KB 配不同 defaultStrategy
  3. 记录各自完成时间
断言：
  - 5 个 KB 全部 parse_status=COMPLETED
  - FIXED_WINDOW < MARKDOWN_HEADING < RECURSIVE < SEMANTIC（SEMANTIC 需调 embedding，最慢）
  - 5 个加起来 ≤ 60s（基线）

== 禁止项 ==
- 不能为通过用例硬编码 chunker_params（必须按 KB profile 配置）
- 不能跳过 fallbackChain 验证（t9-acc-02 就是验证这个）
- SEMANTIC 策略不能 mock，必须真调 DashScope embedding
- AB lab 测试必须真渲染 EvaluationLab.vue 而不是直调 API

== 验收门槛 ==
1. 10 条全绿，trace.zip 归档
2. AB lab 用例必须有 UI 截图证明 4 列对比
3. PR 标题：test(v5/T9): playwright headed acceptance suite (10 cases)
4. 完成后在 docs/v5-execution-tasks.md 末尾追加：- T9 验收 ✅ <commit-sha> 2026-MM-DD（10/10 PASS）
```

`=== COPY END ===`

---

## 任务 T10：多模态一期验收（10 个用例）

`=== COPY START ===`

```
角色：你是 RAGForge 项目的 Cursor 执行者，跟 @guandezhi 架构师协作。本任务执行 T10 多模态一期的
端到端 Playwright 验收测试，**必须 headed 模式让架构师肉眼看图文混合 PDF 切片+检索**。

任务：T10 多模态一期 Playwright e2e 验收（10 个用例）
依赖：T10 + T10-followup + T10-rewrite 全部合并（commits 33dd728 / 6035e33 / dc5629a / 5475e36 / 70d4c6c）
   T8 + T9 验收已 PASS（多模态依赖 cleaner + chunker）
   本地 DashScope qwen-vl-ocr 和 qwen3-vl-embedding API key 必须可用
工期：2 天

必读上下文：
1. backend/src/main/java/com/ragforge/pipeline/image/
   - ImagePipelineService.java（纯图片走这个）
   - RemoteOcrClient.java（qwen-vl-ocr）
   - EmbeddedImageExtractor.java（从 PDF/Word/HTML/MD 抽出嵌入图）
2. backend/src/main/java/com/ragforge/pipeline/embedder/DashScopeVlEmbeddingClient.java
   - VL_DIMENSION = 2560，混合 image+text 统一向量空间
3. backend/src/main/resources/db/manual/V27__vl_unified_vector.sql
   - document_chunks.vl_vector vector(2560)
   - HNSW index
4. backend/src/main/java/com/ragforge/search/RetrievalService.java
   - 默认 hybrid（vector + BM25 + RRF + rerank）
5. 现有 e2e t10-rewrite 用例（参考但不重复）：
   frontend/tests/e2e/t10-rewrite/t10rw-e2e-{01..14}.spec.ts

测试目录：frontend/tests/e2e/v5-acceptance/t10/
测试运行：npx playwright test tests/e2e/v5-acceptance/t10 --headed --workers=1
trace 归档：frontend/test-results/v5-acceptance/t10/

== fixture 准备（重要：要覆盖图文混合）==
1. mm-pure-image-with-text.png：含明显中文文字的截图（如"今日营收 12345 元"），用于纯 OCR
2. mm-pure-image-no-text.png：纯风景图（猫狗等），无文字
3. mm-pdf-text-only.pdf：3 页纯文字 PDF
4. mm-pdf-image-only.pdf：3 页 PDF 每页一张大图无文字
5. mm-pdf-mixed-rich.pdf：**重点 fixture** —— 5 页 PDF，每页含：
   - 上半页 200 字中文段落
   - 中间一张含字图（如图表截图含 "Q1 收入 100w"）
   - 下半页一张纯插图
   - 必须真实可解析（用 reportlab / Pages / Word 导出，不能伪造）
6. mm-word-with-embedded-images.docx：Word 文档含 3 张内嵌图 + 文本段落
7. mm-html-img-tag.html：HTML 含 <img src> + 文本
8. mm-markdown-img-syntax.md：Markdown 含 ![alt](path) + 文本
9. mm-corrupt-image.png：截断的损坏 PNG，header 正常但 data 损坏
10. mm-oversized-image.png：> 10MB 的大图，验证 OCR 超时/限流

== 10 个用例设计 ==

【t10-acc-01-pure-image-ocr-chunk.spec.ts】
目标：上传 mm-pure-image-with-text.png，单图必须产出 1 个 IMAGE chunk + OCR 提取的文本
步骤：
  1. KB 默认 chunker + cleaner
  2. 上传图片
  3. 等完成
断言：
  - chunk_count = 1，chunk_modality = 'IMAGE'
  - chunk.content 含 "今日营收 12345 元" 或近似（OCR 准确率 ≥ 80%）
  - chunk.vl_vector 维度 = 2560
  - chunk.image_key 非空，可拼出 presigned URL 在浏览器打开

【t10-acc-02-pdf-mixed-rich-content.spec.ts】
目标：**【最关键用例】** 图文混合 PDF 必须同时产出 TEXT chunk + IMAGE chunk，且都进 vl_vector 同一空间
步骤：
  1. KB 默认
  2. 上传 mm-pdf-mixed-rich.pdf（5 页混合）
  3. 等完成
断言：
  - chunk_count ≥ 10（5 页 × 至少 2 个 chunk 类型）
  - 同时存在 chunk_modality='TEXT' 和 chunk_modality='IMAGE' 的 chunk
  - 所有 chunks 的 vl_vector 维度均为 2560
  - IMAGE chunks 的 content 含图中文字（如 "Q1 收入 100w"）
  - 浏览器 DocumentDetail 页能看到带预览图的 IMAGE chunk 卡片

【t10-acc-03-image-no-text-handling.spec.ts】
目标：纯风景图无文字时不应该 FAIL，应产出 IMAGE chunk 但 OCR text 为空 / 占位
步骤：
  1. 上传 mm-pure-image-no-text.png
  2. 等完成
断言：
  - parse_status=COMPLETED
  - chunk_count = 1
  - chunk.content 为空或含 "[no text detected]" 或类似占位（按实现校验）
  - chunk.vl_vector 仍非空 2560 维（图像本身 embedding）

【t10-acc-04-text-search-finds-image-ocr.spec.ts】
目标：纯文本检索 query 必须能召回 IMAGE chunk（统一向量空间核心证明）
步骤：
  1. KB 上传 mm-pure-image-with-text.png（含 "今日营收 12345 元"）
  2. DebugConsole 页发起 query="今日营收"
  3. 默认 strategy=hybrid
断言：
  - 搜索结果至少 1 条 chunk_modality='IMAGE'
  - finalScore > 0.5
  - UI 显示图片缩略图 + OCR snippet

【t10-acc-05-image-search-by-image.spec.ts】
目标：POST /api/v1/search/by-image 上传图片必须能召回相似图片 chunk
步骤：
  1. KB 上传 3 张同主题图片（如都是猫的不同角度）
  2. UI 上点击 "图搜图" 入口（如有；否则直调 API）
  3. 上传第 4 张同主题图
断言：
  - 返回 topK 中 ≥ 2 条 chunk_modality='IMAGE'
  - 相似度分数显示且降序

【t10-acc-06-word-embedded-images.spec.ts】
目标：上传含内嵌图的 Word，必须分别为图和文本产出 chunk
步骤：
  1. 上传 mm-word-with-embedded-images.docx
  2. 等完成
断言：
  - 同时存在 TEXT + IMAGE chunk
  - 嵌入图数量 ≥ 3 个 IMAGE chunk

【t10-acc-07-html-img-src-extracted.spec.ts】
目标：HTML 文档 <img src> 必须被解析为 IMAGE chunk
步骤：
  1. 上传 mm-html-img-tag.html
  2. 等完成
断言：
  - IMAGE chunks 数量 = HTML 中 <img> 标签数
  - 每个 IMAGE chunk 的 content 含 OCR 出的文字（如果原图有字）

【t10-acc-08-corrupt-image-graceful.spec.ts】
目标：损坏图片必须 graceful fail，单图失败不阻塞整文档
步骤：
  1. 构造一个 PDF 含 1 张正常图 + 1 张 mm-corrupt-image.png
  2. 上传
  3. 等完成
断言：
  - parse_status=COMPLETED（不是 FAILED）
  - documents.warnings 含 "IMAGE_OCR_FAILED" 或类似
  - 至少正常图的 chunk 存在
  - 损坏图无产出 chunk 或产出 chunk 含错误占位

【t10-acc-09-rebuild-after-rewrite.spec.ts】
目标：T10-rewrite 后老数据可通过 reprocess 重建到 vl_vector 空间
步骤：
  1. 直接 SQL insert 一条假的 documents 行带 vector(1024) 旧维度（mock 历史数据；如果已经清空，则上传一份新数据后手动改维度模拟）
  2. UI 点击 reprocess
  3. 等完成
断言：
  - 新 chunks 全部 vl_vector 维度 = 2560
  - 不再有 content_vector(1024) 残留
  - reprocess 完成时长 ≤ 30s（500 chunks 量级）

【t10-acc-10-cost-and-perf-observation.spec.ts】
目标：观测一次完整 5 页混合 PDF 的 DashScope 调用次数和耗时
步骤：
  1. 后端 metrics 起步 snapshot
  2. 上传 mm-pdf-mixed-rich.pdf
  3. 等完成
  4. /actuator/prometheus 拉指标
断言：
  - ragforge_ocr_qwen_vl_ocr_calls 增量 ≥ 5（每页至少调一次）
  - ragforge_embedding_vl_calls 增量 ≥ 10（text + image 都调）
  - ragforge_worker_processing_duration_seconds.modality=image 有数据
  - 整体耗时 ≤ 60s
  - 单文档 DashScope 调用估算成本 ≤ ¥0.5

== 禁止项 ==
- 不能用 mock OCR / mock embedding（必须真调 DashScope）
- 不能跳过图文混合 PDF 用例（这是 T10 一期的核心交付）
- 不能用旧 modality='image' API 接口（已 deprecated，参考 t10rw-e2e-13）
- fixture 必须真实图文（用截图 + reportlab，不允许 base64 假数据）
- 不能用 t10-rewrite 已有 fixture（独立 fixture 验证可重复性）

== 验收门槛 ==
1. 10 条全绿
2. t10-acc-02 必须有 headed 模式录屏（架构师肉眼看 chunk 列表含图+文）
3. t10-acc-10 成本估算附 PR
4. PR 标题：test(v5/T10): playwright headed multimodal acceptance (10 cases)
5. 完成后在 docs/v5-execution-tasks.md 末尾追加：- T10 验收 ✅ <commit-sha> 2026-MM-DD（10/10 PASS）
```

`=== COPY END ===`

---

## 任务 T11：Answer-as-LLM 应答层验收（10 个用例）

`=== COPY START ===`

```
角色：你是 RAGForge 项目的 Cursor 执行者，跟 @guandezhi 架构师协作。本任务执行 T11 Answer-as-LLM
的端到端 Playwright 验收测试，headed 模式，**重点验证 SSE 流式、引用准确性、GuardRails 拦截、PII 防泄漏**。

任务：T11 Answer-as-LLM Playwright e2e 验收（10 个用例）
依赖：T11 + T11 hotfix（commits 已部署到 main）；T8/T9/T10 验收已 PASS
   后端 model 配置：默认 qwen-plus，KB.answer_model 可显式覆盖
工期：2 天

必读上下文：
1. backend/src/main/java/com/ragforge/answer/AnswerService.java
   - AnswerService / PromptBuilder / CitationLinker / GuardRails 四类同文件
   - GuardRails 只有 NO_CITATIONS + PII_LEAK 检查（OUT_OF_SCOPE 已被删）
   - safeResultsForStream() 推送前对 chunk content 做 piiMaskCleaner.mask()
2. backend/src/main/java/com/ragforge/controller/AnswerController.java
   - POST /api/v1/answer SSE
3. backend/src/main/resources/db/migration/V28__answer_as_llm.sql + V29__correct_answer_model_default.sql
   - answer_logs / knowledge_bases.answer_mode / answer_model 默认 qwen-plus
4. frontend/src/views/AnswerPlayground.vue
   - 答题台 UI，含 KB 选择、流式答案区、引用区
5. frontend/tests/e2e/v5-acceptance/t10/fixtures/mm-pdf-mixed-rich.pdf（T10 已存）

测试目录：frontend/tests/e2e/v5-acceptance/t11/
测试运行：npx playwright test tests/e2e/v5-acceptance/t11 --headed --workers=1
trace 归档：frontend/test-results/v5-acceptance/t11/

== fixture 准备 ==
1. answer-kb-tech-faq.txt：100 条技术 FAQ（如 "Spring Boot 3.5 默认端口是 8080"）
2. answer-kb-pii-mixed.txt：含 PII 的文档（手机/邮箱），用于验证 SSE 推送脱敏
3. answer-kb-multilingual.txt：中英文 FAQ 混合
4. answer-kb-numerical.txt：含具体数字的事实（如 "公司 2024 年营收 10 亿"）
5. answer-kb-conflicting.txt：含相互矛盾的两段（用于测试 LLM 选择哪一段引用）
6. answer-kb-empty.txt：空文件或纯空白

== 10 个用例设计 ==

【t11-acc-01-streaming-token-by-token.spec.ts】
目标：SSE 必须真流式 token-by-token 推送，前端答案区逐字出现
步骤：
  1. 创建 KB，上传 answer-kb-tech-faq.txt，等完成
  2. AnswerPlayground 页输入 "Spring Boot 3.5 默认端口是多少"
  3. 提交，监听 SSE
断言：
  - SSE 收到 ≥ 5 个 token event（不是一次性全推）
  - 前端 .answer-text 文本长度随时间递增（每 200ms 拍一次快照对比）
  - 最终答案含 "8080"
  - GuardRailResult = "PASS"

【t11-acc-02-citation-link-to-chunk.spec.ts】
目标：每个 [n] 引用必须能点击跳到对应 chunk，chunkId 在 retrieval event 中提前推送
步骤：
  1. 同上 KB，问 "Spring Boot 3.5 默认端口"
  2. 等完整答案
  3. 点击答案中的 [1] 引用
断言：
  - 答案含至少 1 个 [n] 形式引用
  - 引用区显示对应 chunk content snippet
  - 点击跳转到 DocumentDetail 页对应 chunk
  - retrieval event 在 token event 之前到达

【t11-acc-03-no-citation-guardrail.spec.ts】
目标：当检索结果为空时，必须返回 "未找到相关信息" 答案，且 GuardRailResult=NO_CITATIONS 拦截（如果 LLM 强答）
步骤：
  1. 上传 answer-kb-empty.txt（无内容）
  2. 问 "公司 2024 年营收"
断言：
  - 答案是预设 NOT_FOUND_ANSWER 字符串
  - response.citations 是空数组
  - GuardRailResult ∈ {PASS, NO_CITATIONS}（PASS 是因为预设答案合规）
  - SSE 收到 complete event

【t11-acc-04-pii-leak-guardrail.spec.ts】
目标：LLM 答案如果回答出 PII 必须被 GuardRails PII_LEAK 拦截
步骤：
  1. 上传 answer-kb-pii-mixed.txt（含手机号 138xxx）
  2. 强行问 "联系电话是多少" （引导 LLM 输出 PII）
断言：
  - 必须二选一：
    a) 答案中无原始 PII（被前置脱敏，PASS）
    b) GuardRailResult = "PII_LEAK"，前端显示拦截提示
  - 不允许：答案含原始手机号 + GuardRailResult=PASS（这是漏网）

【t11-acc-05-sse-retrieval-pii-masked.spec.ts】
目标：SSE retrieval event 中推送的 chunk content 必须经过 PII MASK，前端看不到原始 PII
步骤：
  1. 上传 answer-kb-pii-mixed.txt
  2. 问任意问题触发检索（如 "联系方式"）
  3. 抓取 SSE retrieval event
断言：
  - retrieval.chunks[i].content 不含原始手机号/邮箱/身份证（regex 验证）
  - 含掩码占位（138****5678 或类似）
  - chunkId / docId / scores 等元数据完整保留

【t11-acc-06-answer-mode-off-blocks.spec.ts】
目标：KB.answer_mode='OFF' 时调用必须返回 403 ANSWER_DISABLED
步骤：
  1. 创建 KB，answer_mode='OFF'
  2. 上传 answer-kb-tech-faq.txt
  3. 调 /api/v1/answer
断言：
  - HTTP 403 或 SSE error event code=ANSWER_DISABLED
  - 前端 UI 显示"该 KB 未启用应答模式"
  - answer_logs 表无新增记录

【t11-acc-07-default-model-qwen-plus.spec.ts】
目标：KB 未显式设 answer_model 时必须默认走 qwen-plus
步骤：
  1. 创建 KB，不设 answer_model（NULL）
  2. 上传 + 提问
  3. 看 answer_logs 表
断言：
  - answer_logs.llm_model = 'qwen-plus'
  - 不能是 'qwen-max' 或其他
  - 答案 latency.llm > 0

【t11-acc-08-multi-kb-citation-merge.spec.ts】
目标：跨多个 KB 时引用必须正确归属到原 KB，answer_logs.kb_ids_csv 含全部
步骤：
  1. KB-A 上传 answer-kb-tech-faq.txt
  2. KB-B 上传 answer-kb-numerical.txt
  3. AnswerPlayground 同时选 A + B，问一个跨两 KB 的问题
断言：
  - 答案含分别来自 A 和 B 的引用
  - answer_logs.kb_ids_csv = "<idA>,<idB>"
  - citations 中 docId 分布在两个 KB

【t11-acc-09-streaming-cancel.spec.ts】
目标：前端用户中途取消 SSE，后端必须及时停止 LLM 调用（不能继续 burn token）
步骤：
  1. 提交一个会产生长答案的问题
  2. token event 收到 3 个后，前端关闭 EventSource
  3. 等 5 秒后查 answer_logs
断言：
  - 收到的 token 长度 < 完整答案长度
  - 后端日志含 "SSE_CLIENT_DISCONNECTED" 或类似
  - answer_logs 仍有记录（部分答案 + completion_tokens < 200）
  - 不会因为客户端断开而 500

【t11-acc-10-perf-baseline-and-cost.spec.ts】
目标：观测端到端延迟、token 用量、引用数和成本
步骤：
  1. 顺序问 10 个不同 query
  2. 拉 prometheus / answer_logs 算平均
断言：
  - 平均 total_latency_ms < 5000
  - 平均 prompt_tokens < 2000（提示词压缩合理）
  - 平均 citations 数 ∈ [1, 5]
  - ragforge_answer_citations_total / ragforge_answer_retrieval_results_total ≥ 0.4（引用利用率）
  - 10 次累计 DashScope 估算成本 ≤ ¥1

== 禁止项 ==
- 不能用 mock LLM（必须真调 qwen-plus）
- 不能跳过 SSE 真流式验证（t11-acc-01 要拍多个时间快照）
- 不能依赖 retrieval mock（真走 hybrid + 真 chunk）
- 不能为通过 t11-acc-04 给 LLM 加 system prompt 让它"不说手机号"（违反真实场景）

== 验收门槛 ==
1. 10 条全绿
2. t11-acc-04 / t11-acc-05 / t11-acc-09 三条核心安全用例必须各附录屏
3. t11-acc-10 成本报告 + 性能基线表附 PR
4. PR 标题：test(v5/T11): playwright headed answer-as-llm acceptance (10 cases)
5. 完成后在 docs/v5-execution-tasks.md 末尾追加：- T11 验收 ✅ <commit-sha> 2026-MM-DD（10/10 PASS）
6. 任何一条 FAIL 必须区分：是 AnswerService 真 bug、是 LLM 不稳定（重试 3 次仍失败才算 bug）、还是测试设计错
```

`=== COPY END ===`

---

## 任务 Q：质量看板验收（10 个用例）

`=== COPY START ===`

```
角色：你是 RAGForge 项目的 Cursor 执行者。本任务为质量看板（LLM-as-Judge 看板）写
10 个 Playwright headed-mode e2e 用例。**必须 --headed --workers=1 跑给架构师肉眼看**。

任务：Q 质量看板 Playwright e2e 验收（10 个用例）
依赖：J1-J7 + V6 P0/P1 hotfix 全部合并；DeepSeek-V4-Flash 已接入；
   本地 backend / frontend / PG / DeepSeek 可用
工期：1.5-2 天

== 必读上下文 ==
1. backend/src/main/java/com/ragforge/controller/JudgeQualityController.java
   5 个端点：/overview /by-kb /worst-cases /case/{id} /cost
2. backend/src/main/java/com/ragforge/controller/JudgeSamplingController.java
   抽样配置 CRUD（ADMIN-only，超过 10% 必须 confirmed=true）
3. backend/src/main/java/com/ragforge/controller/GoldenSetController.java
   回放（含真分布式锁 409 互斥）
4. frontend/src/views/EvaluationQuality.vue
   看板主页（KPI / 趋势图 / KB 切片 / 最差 case / 成本 / 抽样设置抽屉）
5. frontend/src/views/EvaluationQualityCase.vue
   case 详情页（query / answer / chunks / 评分 / reasoning）
6. frontend/src/router/index.js
   /evaluation/quality 和 /evaluation/quality/case/:id 路由
7. backend V30 migration + judge_results / judge_metrics_daily / judge_sampling_config 三张表

== 测试目录 + 命名 ==

frontend/tests/e2e/v5-acceptance/quality/
  q-acc-01-route-permission.spec.ts
  q-acc-02-empty-state.spec.ts
  q-acc-03-kpi-and-trend.spec.ts
  q-acc-04-time-range-switch.spec.ts
  q-acc-05-kb-filter.spec.ts
  q-acc-06-kb-slice-table.spec.ts
  q-acc-07-worst-case-detail.spec.ts
  q-acc-08-cross-kb-access-deny.spec.ts
  q-acc-09-sampling-drawer.spec.ts
  q-acc-10-golden-replay.spec.ts

运行命令：npx playwright test tests/e2e/v5-acceptance/quality --headed --workers=1
trace 归档：frontend/test-results/v5-acceptance/quality/

== 共享 helper ==

frontend/tests/e2e/v5-acceptance/_helpers/judge-data-seeder.ts 提供：
  - seedJudgeResult({ kbIds, source, status, faithfulness, contextPrecision,
      answerRelevance, overallScore, createdAt, judgeRawResponse, ... })
    通过新增的 admin endpoint POST /api/v1/admin/e2e/judge-result 写库
    （endpoint 仅 dev/test profile 启用，prod 不存在）
  - clearJudgeResults({ tenantId })  afterEach 清理
  - triggerAggregator()  调 admin endpoint 触发 JudgeMetricsAggregator.aggregate()
  - ensureGoldenQuestion({ kbId, judgeEnabled: true })

frontend/tests/e2e/v5-acceptance/_helpers/kb-acl-seeder.ts 提供：
  - grantKbAccess(account, kbId, 'read'|'write')
  - revokeKbAccess(account, kbId)
  - 用 admin API 直接操作 kb_acl 表

frontend/tests/e2e/v5-acceptance/_helpers/global-setup.ts：
  - 校验 GET /actuator/health = 200
  - 校验 GET /api/v1/admin/e2e/judge-result/_ping = {status:"ok"}
    （证明 AdminE2eJudgeController 已加载，没加载就 throw "请重启 backend"）
  - 不自动 spawn 后端进程
  playwright.config.js 的 globalSetup 指向此文件

== 10 个用例设计 ==

【q-acc-01-route-permission】
目标：路由角色闸门
准备：
  - admin / qa_kbeditor / qa_viewer 三个账号
步骤：
  1. 不登录直接访问 /evaluation/quality → 重定向到 /login
  2. qa_viewer 登录 → 访问 → 跳 /forbidden 或路由守卫拦截
  3. qa_kbeditor 登录 → 能进入页面
  4. admin 登录 → 能进入页面 + 看到右上角"设置"按钮
  5. qa_kbeditor 点击"设置"按钮调 POST /sampling
断言：
  - 不登录跳 /login，**UI 不能短暂闪现"质量看板"标题**（防止权限旁路）
  - qa_viewer 访问 → 页面显示"您没有访问此页面的权限"或类似中文友好文案
    （**不能显示 "Forbidden 403" 英文**）
  - qa_kbeditor 调 sampling → 后端 403 → toast 显示"只有管理员可以修改抽样配置"
    （**不能显示 SAMPLE_RATE_TOO_HIGH_REQUIRES_CONFIRM 或 traceId**）

【q-acc-02-empty-state】
目标：空数据态友好降级（颜色规范的核心证明）
准备：
  - admin 登录，clearJudgeResults 清空 + triggerAggregator 让 judge_metrics_daily 也空
步骤：
  1. 进入 /evaluation/quality
  2. 截屏给架构师看
断言：
  - 4 个 KPI value 显示 "0.00"，CSS class 含 score-muted（灰色 #cbd5e1）
  - **不能出现红色 0.00**（这是用户痛点）
  - 4 个 KPI trend 区显示 "→ 0.00"（不显示 ↑↓ 也不显示 NaN）
  - 趋势图区显示"暂无评测数据，请检查 Golden Set 是否启用"中文文案
    （**不能空白或显示英文 "No data"**）
  - 不显示异常告警 banner（没数据时 anomaly 不应触发）
  - 成本卡显示 "¥0.00" 或 "暂无成本数据"（不能显示 ¥NaN）
  - 截屏存 frontend/test-results/v5-acceptance/quality/q-acc-02-empty-state.png

【q-acc-03-kpi-and-trend】
目标：有数据时 KPI 颜色 + 趋势图渲染 + hover 交互
准备：
  - admin 登录
  - seed 14 天每天 5 条 judge_results：
    * 前 7 天 overallScore = 0.5（黄色基线）
    * 后 7 天 overallScore = 0.85（绿色提升）
    * 4 dimension 各自不同分数（faithfulness 0.8 / contextPrecision 0.7 / answerRelevance 0.85）
  - triggerAggregator()
步骤：
  1. 进入页面
  2. hover 趋势图中间数据点
  3. 取消勾选"答案忠实度" metric
断言：
  - 综合质量 KPI = 0.85，class=score-green
  - overall trend = ↑ +0.35（绿色 ↑）
  - 答案相关性 KPI = 0.85 绿色，上下文精度 = 0.7 黄色
  - 趋势图 SVG 含 4 条 path
  - hover 数据点 → trend-hover-card 出现，显示当日 sample count + 4 个 score
    （hover-card 文案不能显示原始时间戳格式 "1782xxxx"，必须 "2026-06-22"）
  - 取消勾选忠实度 → SVG path 减少到 3 条
  - 成本卡显示真实金额（如 "¥0.42"），不显示 ¥NaN
  - 截屏存档

【q-acc-04-time-range-switch】
目标：7/30/90 天切换 + 视觉反馈
准备：
  - admin 登录
  - seed 60 天每天数据
步骤：
  1. 默认 7 天进入页面
  2. 点 30 天按钮 → 等 GET /overview?days=30 响应
  3. 点 90 天按钮
  4. 切回 7 天
断言：
  - 每次切换 Network 抓到 GET /overview 请求带正确 days 参数
  - active 按钮蓝边 + 蓝字 + font-weight:600（视觉验证）
  - 非 active 按钮白底灰边（视觉验证）
  - 趋势图 X 轴标签数量随天数变化（7 天 ≤7 个 label，90 天 ≤30 个 label）
  - 切换不导致整页 reload（loading.overview 短暂闪现但不全屏空白）
  - 切换后若数据加载失败（mock 后端 500）→ toast "数据加载失败，请刷新重试"
    （**不能显示 "request failed with status 500"**）

【q-acc-05-kb-filter】
目标：KB 筛选输入 + 错误友好
准备：
  - admin 登录，准备 3 个 KB 各 seed 不同分数
步骤：
  1. KB 筛选输入 kbId=<KB-1> → 应用
  2. 输入 kbId=9999999（不存在）→ 应用
  3. 输入 "abc" 非数字 → 应用
  4. 输入 -1 负数 → 应用
  5. 点击清除
断言：
  - 应用 KB-1 → GET /overview?kbId=<id>，数据只反映该 KB
  - 不存在 kbId → 后端 403 → toast 显示"无权访问该知识库或知识库不存在"
    （**不能显示 KB_ACCESS_DENIED 或 traceId**）
  - 非数字输入 → 前端校验拦截，**不发请求**，提示"请输入有效的知识库 ID（数字）"
  - 负数 → 同上前端拦截
  - 清除 → kbId 参数从 URL/请求里移除，恢复全量

【q-acc-06-kb-slice-table】
目标：KB 切片表升序 + 颜色 + 行点击下钻
准备：
  - admin 登录，3 个 KB seed 不同分数：0.4 / 0.7 / 0.9
步骤：
  1. 进入页面
  2. 观察 KB 切片表
  3. 点击分数 0.4 那行
断言：
  - 表格按 overall_score 升序（最差在上）
  - 每行显示：KB 名 / 分数（带颜色）/ 趋势箭头 / 样本数
  - 0.4 那行分数 span class=score-red，0.7=score-amber，0.9=score-green
  - **KB 名为空时显示 "KB 1"（fallback），不能显示空白单元格或 "undefined"**
  - 点击行触发路由 push /evaluation/quality?kbId=<id>
  - 跳转后 KB 筛选输入框自动填充该 kbId
  - 趋势图 + KPI 反映该 KB 数据
  - 表格无数据时显示"暂无可见评测数据"（不能空白）

【q-acc-07-worst-case-detail】
目标：最差 case 列表 + 跳详情页 + 详情内容完整
准备：
  - admin 登录，seed 30 条 judge_results
  - judge_raw_response JSONB 字段含真实结构：{ faithfulness: {score, reasoning, issues}, ... }
步骤：
  1. 进入看板，观察最差 10 个 case 列表
  2. 点击分数最低的 case
  3. 详情页：点击"返回"按钮
断言：
  - 列表按 overall_score 升序，最多 10 条
  - 每条显示 query（≤50 字 + "..." 截断）+ 分数 + 创建时间（"2026-06-22 18:32" 格式，不能 ISO8601 原始）
  - 点击跳 /evaluation/quality/case/{id}
  - 详情页显示：
    * Query 原文完整（含换行）
    * Generated Answer 含 [n] 引用
    * 4 个 dimension 评分（每个 0.00-1.00 格式）
    * bottleneck 标签（中文化：RETRIEVAL → "检索瓶颈"，GENERATION → "生成瓶颈"，BOTH → "两者皆有"）
    * 检索 chunks 列表，每个 chunk 显示 score + content snippet（≤200 字）
    * relevant=false 的 chunk 灰色 + 标记 "⚠ 不相关"
    * DeepSeek reasoning 折叠面板（默认收起）
    * 改进建议列表（bullet point）
  - 点击"返回"回到看板，时间范围保持不变
  - 详情页加载失败（mock 404）→ "案例不存在或已被删除"（不能 404 NOT_FOUND 英文）

【q-acc-08-cross-kb-access-deny】（V6 P0 hotfix B3 验收）
目标：跨 KB 信息泄露防御
准备：
  - admin 登录，创建 KB-A 和 KB-B
  - grantKbAccess(qa_kbeditor, KB-A, 'read')，**KB-B 不授权**
  - seed 一条 case 关联 KB-B（query/answer 含敏感内容）→ 拿到 judgeResultId
步骤：
  1. qa_kbeditor 登录
  2. 直接拼路径访问 /evaluation/quality/case/{judgeResultId}
  3. qa_kbeditor 访问不存在的 judgeResultId=99999999
断言：
  - 后端 GET /case/{id} 返回 403 KB_ACCESS_DENIED
  - 前端 UI 显示"无权访问该案例"或"该案例属于您没有权限的知识库"
    **不能渲染 query / answer / chunks 任何字段（哪怕 1 个字符）**
  - DevTools Network 抓包确认响应 status=403，response body 不含敏感字段
  - 控制台无 console.error 暴露后端 stack
  - 不存在的 judgeResultId → 404 → UI "案例不存在"（不能 JUDGE_RESULT_NOT_FOUND）
  - admin 同样路径 → 200，正常渲染（对照组）

【q-acc-09-sampling-drawer】
目标：抽样设置抽屉（ADMIN-only + 超 10% 二次确认）
准备：
  - admin 登录
步骤：
  1. 点击右上角"设置"按钮
  2. slider 拖到 3% → 保存
  3. slider 拖到 8% → 保存（< 10%，无需确认）
  4. slider 拖到 15%，不勾"我已确认成本风险" → 保存
  5. 勾选确认 → 保存
  6. KB 覆盖区添加一个 KB（rate=5%）→ 保存
  7. 删除刚加的 KB 覆盖
  8. 关闭抽屉
断言：
  - 抽屉打开后 slider 显示当前全局抽样率（GET /sampling 返回的值）
  - 3% / 8% 保存 → 200 + toast "抽样配置已更新"
  - 15% + 未勾确认 → 后端 400 → toast 显示
    "当前抽样率超过 10%，月度成本会显著增加，请确认后再保存"
    **不能显示 SAMPLE_RATE_TOO_HIGH_REQUIRES_CONFIRM**
  - 15% + 勾确认 → 200，sample_rate=0.15 落库
  - KB 覆盖 add → 200，列表新增一行
  - KB 覆盖 delete → 列表减少一行
  - 关闭抽屉 → 状态清理（重开后 slider 值是最新落库值）
  - qa_kbeditor 登录访问同样动作 → 403 → toast "只有管理员可以修改抽样配置"

【q-acc-10-golden-replay】（拆 3 个子断言，因 DeepSeek 真链路慢）
目标：Golden Set 回放 + 真分布式锁 + 跨 KB 权限
准备：
  - admin 登录
  - 创建 eval_dataset（kbId=<KB-A>）+ 3 个 eval_question，judge_enabled=true
  - grantKbAccess(qa_kbeditor, KB-A, 'read')

子断言 A（分布式锁，30 秒内）：
  1. 进入设置抽屉，看到"当前启用题数: 3"
  2. 点击"立即回放"按钮 → 200 + toast "回放已开始"
  3. 立刻再点一次"立即回放" → 后端 409
断言：
  - 第二次点击 → toast 显示"已有回放任务正在进行，请稍后再试"
    **不能显示 REPLAY_ALREADY_RUNNING**
  - 按钮在第一次点击后立即 disabled，hover 显示"任务进行中"

子断言 B（数据可见性，10 秒内，绕过真 DeepSeek）：
  1. 用 seedJudgeResult 直接造 3 条 source=GOLDEN_SET 的 judge_results
  2. triggerAggregator()
  3. 进入看板
断言：
  - 趋势图正确显示
  - 成本卡 "按来源" 维度看到 GOLDEN_SET 占比 > 0
  - KPI 综合质量反映这 3 条

子断言 C（@local-only，可选，120 秒长 timeout）：
  1. admin 触发 /replay，等待 judge_results 出现 source=GOLDEN_SET + status=COMPLETED 行
断言：
  - 1 题 datasetId 至多等 120 秒
  - 失败重试 1 次仍失败 → test.skip()，不阻塞 PASS
  - DeepSeek 真接口冒烟（这条是质量验收，不是功能验收）

子断言 D（跨 KB 权限）：
  1. qa_kbeditor 登录，调 POST /replay datasetId=<KB-B's dataset>（无权 KB）
断言：
  - 403 → toast "您没有访问该数据集对应知识库的权限"
  - 不调 /replay datasetId=null + qa_kbeditor → toast "请选择具体的数据集（管理员才能跑全量回放）"

== 禁止项 ==

- 不能用 mock 后端（必须真后端，但 DeepSeek 部分用 seed 数据绕过：
  seedJudgeResult 直接写表带预设分数）
- 不能 skip q-acc-02 "暂无数据" 用例（这是颜色规范的核心证据：红色 0.00 痛点的回归）
- 不能跨 case 共享 seed 数据（每个 case afterEach 清自己的）
- 不能 hardcode kbId / judgeResultId（用 seed 函数返回的 id）
- 不能跳过 q-acc-08（跨 KB 权限是 P0 安全验收）
- 不能用 page.evaluate 注入 mock fetch 跳过后端
- 不能用 sleep 等数据 —— 必须 expect.poll 轮询
- **不能在任何一处错误断言里检查英文 error code 或 traceId**

== 错误友好文案对照表（必须实现） ==

| 后端 code | 前端 UI 文案 |
|---|---|
| 403 KB_ACCESS_DENIED | "无权访问该知识库或案例" |
| 403 KB_WRITE_FORBIDDEN | "您没有该知识库的写权限" |
| 403 SAMPLING_ADMIN_ONLY | "只有管理员可以修改抽样配置" |
| 400 SAMPLE_RATE_TOO_HIGH_REQUIRES_CONFIRM | "当前抽样率超过 10%，月度成本会显著增加，请勾选确认后再保存" |
| 404 JUDGE_RESULT_NOT_FOUND | "案例不存在或已被删除" |
| 404 EVAL_DATASET_NOT_FOUND | "数据集不存在" |
| 409 REPLAY_ALREADY_RUNNING | "已有回放任务正在进行，请稍后再试" |
| 403 DATASET_ID_REQUIRED | "请选择具体的数据集（管理员才能跑全量回放）" |
| 500 任何 | "服务暂时异常，请稍后重试" |
| 网络错误 | "网络连接失败，请检查网络后重试" |

如果前端代码当前没有这些映射，**新增 frontend/src/api/error-messages.js** 集中维护，
然后在 EvaluationQuality.vue / 抽屉 / 详情页的 catch 里调用 errorMessage(e)。

== 验收门槛 ==

1. 10 条全绿（含 q-acc-10 的 4 个子断言），trace.zip + screenshot 全部归档到
   frontend/test-results/v5-acceptance/quality/
2. q-acc-02 / q-acc-03 必须有截屏证据（颜色规范）
3. q-acc-08 / q-acc-09 / q-acc-10 必须有 DevTools Network 截图（状态码证据）
4. 跑完后 /actuator/prometheus 看到 ragforge.judge.requests / ragforge.judge.cost 增长
5. **错误文案断言至少 12 处通过**（从对照表里挑）
6. PR 标题：test(quality): playwright headed acceptance suite (10 cases)
7. PR 描述附 10 条用例 PASS/FAIL 表 + 失败重试次数

== 执行流程 ==

1. 输出"我将创建的关键文件清单"（≤15 个文件，含 helper + spec + error-messages.js）
2. 提交计划等架构师确认（特别是 admin endpoint 是否允许新增）
3. 写 helper → 写 spec → 跑 --headed 看一遍录屏
4. 全绿后 PR，附录屏 mp4 到 docs/v6-quality-dashboard-acceptance/
```

`=== COPY END ===`

---

## 任务 A：应答 Playground 验收（10 个用例）

`=== COPY START ===`

```
角色：你是 RAGForge 项目的 Cursor 执行者。本任务为应答 Playground（AnswerPlayground.vue）
写 10 个 Playwright headed-mode e2e 用例。**真用户体验验收**，重点是流式动效 +
引用渲染 + 错误友好。

任务：A 应答 Playground Playwright e2e 验收（10 个用例）
依赖：T11 + V6 P0/P1 hotfix；DashScope qwen-plus 可用；KB.answer_mode 必须能改 ON
工期：1.5 天

== 必读上下文 ==
1. backend/src/main/java/com/ragforge/answer/AnswerService.java
   SSE 流式：retrieval / token / complete / error 事件序列
2. backend/src/main/java/com/ragforge/controller/AnswerController.java
   POST /api/v1/answer
3. frontend/src/views/AnswerPlayground.vue
   UI 主体：KB 选择、answerMode select、query 输入、答案区、引用区
4. backend/src/main/java/com/ragforge/answer/AnswerService.java
   GuardRails：NO_CITATIONS / PII_LEAK（OUT_OF_SCOPE 已删）
5. T11 现有 t11-acc-NN 用例（API 层验收），A 系列是 UI 层验收（不重复）

== 测试目录 + 命名 ==

frontend/tests/e2e/v5-acceptance/answer-playground/
  a-acc-01-kb-select-and-form.spec.ts
  a-acc-02-streaming-visible-token.spec.ts
  a-acc-03-citation-cards-render.spec.ts
  a-acc-04-citation-click-to-document.spec.ts
  a-acc-05-answer-mode-off-disable.spec.ts
  a-acc-06-pii-leak-friendly-message.spec.ts
  a-acc-07-no-citations-friendly-state.spec.ts
  a-acc-08-multi-kb-citation-merge.spec.ts
  a-acc-09-cancel-streaming.spec.ts
  a-acc-10-cost-and-performance-baseline.spec.ts

运行命令：npx playwright test tests/e2e/v5-acceptance/answer-playground --headed --workers=1
trace 归档：frontend/test-results/v5-acceptance/answer-playground/

== 共享 helper（复用已有 + 新增）==

frontend/tests/e2e/v5-acceptance/_helpers/answer-kb-seeder.ts 新增：
  - createKbWithAnswerOn({ name, model, answerMode='ON' })
    创建 KB 后立即调 admin API 或 SQL 把 answer_mode='ON'
    （V31 前 DB 默认 OFF，必须每次显式开）
  - uploadDocAndWaitComplete({ kbId, filename, content })
    上传 + 轮询 parse_status='COMPLETED'，超时 60s
  - cleanupKb(kbId)  afterEach 清理（CASCADE 删除 chunks + answer_logs）

frontend/tests/e2e/v5-acceptance/_helpers/sse-listener.ts 新增：
  - subscribeAnswerSse(query, kbIds, opts)
    用 EventSource 监听 /api/v1/answer SSE
    返回 { retrievalEvent, tokenEvents, completeEvent, errorEvent, totalLatencyMs }
  - 用于不走 UI 的纯 API 流验证

== 10 个用例设计 ==

【a-acc-01-kb-select-and-form】
目标：表单基本元素 + KB 默认选中 + answerMode 切换
准备：
  - admin 登录
  - createKbWithAnswerOn({ name: 'a-acc-01-faq', model: 'qwen-plus' })
  - 上传 1 条 .txt FAQ（"RAGForge 是企业级 RAG 知识引擎"）
  - 等 parse_status=COMPLETED
步骤：
  1. 进入 /answer
  2. 观察 KB 下拉默认值
  3. 切换 answerMode：ON → PREVIEW → OFF
  4. 不输入 query，点击"提交"
断言：
  - KB 下拉至少含 a-acc-01-faq KB
  - answerMode 默认显示 "ON"（form.answerMode='ON'）
  - 切换到 OFF → 提交按钮 disabled 或显示提示"该 KB 已停用应答模式"
  - 不输入 query 直接提交 → 前端校验"请输入问题"（**不能 400 BAD_REQUEST 英文**）
  - 输入框 placeholder 中文化：如"请输入您的问题..."

【a-acc-02-streaming-visible-token】
目标：SSE 真流式 token-by-token（视觉验证）
准备：同 a-acc-01
步骤：
  1. 输入"RAGForge 是什么"
  2. 提交
  3. 每 200ms 拍一次答案区 .answer-text 文本快照（拍 10 次）
断言：
  - 至少 5 个时间点观察到不同的文本长度（不是一次性全推）
  - 中途某个快照的文本是"前缀完整 + 末尾不完整"（如 "RAGForge 是一个企业级"）
  - 最终答案非空，且包含 "RAGForge" 关键词
  - SSE 收到 ≥ 5 个 token event（NetworkPanel 抓 EventStream）
  - 答案区有"光标闪烁"或类似 streaming 视觉提示（实现时如有）
  - 中途 token 卡住 > 10 秒 → UI 显示"正在思考..."而不是空白

【a-acc-03-citation-cards-render】
目标：引用卡片正确渲染（含图片缩略图）
准备：
  - createKbWithAnswerOn
  - 上传 1 个 .txt（FAQ 文本）+ 1 张 PNG（含文字"营收 100w"）
  - 等都 COMPLETED
步骤：
  1. 提交"营收数据是多少"
  2. 等 complete event
断言：
  - 答案含 [1] [2] 形式引用
  - 引用区显示 N 张 citation card
  - 每张 card：[n] 标号 + modality 标签（TEXT/IMAGE）+ chunkId
  - IMAGE 类型 card 含 <img> 缩略图（imageUrl 非空）
  - TEXT 类型 card 显示 textSnippet（≤120 字）
  - card 数量 = answer 里 [n] 的数量（不能错位）
  - 图片加载失败 fallback 显示"图片加载失败"占位（不能裸 broken image icon）

【a-acc-04-citation-click-to-document】
目标：引用点击跳转 + chunk 高亮
准备：同 a-acc-03
步骤：
  1. 完整答案出现后
  2. 点击答案中的 "[1]"
  3. 点击引用卡片中的 chunkId 链接
断言：
  - 点击 [1] 滚动到对应 citation card（视觉验证）
  - 点击 chunk 链接跳到 /document/{docId}?chunkId={chunkId}
  - 详情页对应 chunk 高亮显示（边框或背景色）
  - 跳转失败（docId 不存在）→ "源文档已被删除"（不能 404 NOT_FOUND）

【a-acc-05-answer-mode-off-disable】（V6 痛点回归）
目标：KB.answer_mode='OFF' 时 UI 友好降级
准备：
  - 创建 KB 但**不开 answer_mode**（默认 OFF）
  - 上传 1 条 FAQ
步骤：
  1. 进入 /answer 选择该 KB
  2. 试图提交查询
断言：
  - KB 旁显示徽章 "OFF"
  - 提交按钮 disabled + 鼠标 hover 显示 "该知识库的应答模式已关闭，请联系管理员开启"
  - **不能允许用户提交后才报 403 ANSWER_DISABLED**（这是产品体验破坏，前端拦截更友好）
  - 切换到 answer_mode=ON 的 KB → 按钮 enabled
  - 用真 admin 在另一个 tab 打开该 KB 改 answer_mode='ON'，再刷新 → 按钮 enabled
    （证明缓存 invalidate 正确）

【a-acc-06-pii-leak-friendly-message】
目标：PII_LEAK 拦截的用户体验
准备：
  - createKbWithAnswerOn
  - 上传 1 条 .txt 含手机号 13812345678 + 邮箱 alice@example.com
步骤：
  1. 提交"联系方式是什么"（引导 LLM 答出 PII）
  2. 监听 SSE complete event 或 error event
断言：
  - 必须满足下面任一：
    a) GuardRailResult=PASS，答案中 PII 已脱敏（"138****5678"）
    b) GuardRailResult=PII_LEAK，UI 显示
       "答案中检测到敏感信息，已停止显示。请联系管理员调整内容审核策略"
       **不能显示 PII_LEAK 字面值或 BizException**
  - **绝对禁止**：答案完整显示原始手机号（这是安全 bug）
  - 错误 banner 颜色温和（橙/黄），不能用刺眼红色
  - 提示下方有"重新提问"按钮

【a-acc-07-no-citations-friendly-state】
目标：检索空时的用户体验
准备：
  - createKbWithAnswerOn，**不上传任何文档**（或上传一份无关内容）
步骤：
  1. 提交"今天天气怎么样"（KB 内必然无相关）
断言：
  - 答案区显示预设友好答案：
    "在当前知识库中没有找到与您问题相关的内容。建议尝试：1. 换个说法；2. 选择其他知识库；3. 联系管理员补充资料"
    **不能空白 + 不能 NaN + 不能显示 NOT_FOUND**
  - 引用区显示 "暂无引用"（不能 0 个卡片导致区域塌陷）
  - 不显示 [1] 等引用占位
  - latency 字段照常落库（用 SQL 抽查 answer_logs 最新行）

【a-acc-08-multi-kb-citation-merge】
目标：跨多 KB 选择 + 引用来源标识
准备：
  - 创建 KB-A 含 "Spring Boot 默认端口 8080"
  - 创建 KB-B 含 "Vue 3 默认端口 5173"
  - 都 answer_mode=ON
步骤：
  1. 进入 /answer，**多选** KB-A + KB-B（如果当前 UI 只支持单选，本用例改为打 API 验证）
  2. 提交"开发时常用的端口有哪些"
断言：
  - 答案含 Spring Boot 8080 + Vue 5173
  - 引用区显示 2+ citations，每张 card 标注来源 KB 名（如 "来自：KB-A"）
  - 不同 KB 来源 card 边框颜色或标签区分（视觉验证）
  - answer_logs.kb_ids_csv = "<idA>,<idB>"

【a-acc-09-cancel-streaming】
目标：用户中途取消 + 后端真停止
准备：同 a-acc-01
步骤：
  1. 提交一个会产生长答案的问题（"详细介绍 RAGForge 所有功能"）
  2. 收到 ≥ 5 个 token event 后，**点击 UI 的"停止"按钮**
  3. 等 3 秒后查 answer_logs
断言：
  - "停止"按钮在流式过程中可见 + 可点
  - 点击后 EventSource 关闭，答案区固定在已收到的内容
  - 按钮变成"重新提问"或"继续"
  - answer_logs 最新行存在（部分答案）
  - completion_tokens < 完整答案的 token 数（证明真停了）
  - 后端日志含 "SSE_CLIENT_DISCONNECTED" 或类似
  - 不会因为客户端断开而服务端 500

【a-acc-10-cost-and-performance-baseline】
目标：端到端延迟 + token 用量 + 引用率基线
准备：
  - createKbWithAnswerOn + 10 条 FAQ
步骤：
  1. 顺序提交 10 个不同 query
  2. 每次记录端到端 latency（从提交到 complete event）
  3. 抓 prometheus 指标
断言：
  - 平均 total_latency_ms < 5000
  - 平均 prompt_tokens < 2000（提示词压缩合理）
  - 平均 citations 数 ∈ [1, 5]
  - ragforge.answer.citations_total / ragforge.answer.retrieval_results_total ≥ 0.4
  - 10 次累计 DashScope 估算成本 ≤ ¥1
  - P95 latency 显示在 UI 角落（如有）—— 没有就提 V7 加

== 禁止项 ==

- 不能 mock 后端 SSE（必须真 streaming）
- 不能跳过 a-acc-05（answer_mode=OFF 是 V6 痛点，必须前端拦截）
- 不能跳过 a-acc-06（PII 安全 P0）
- 不能允许任何一处直接显示 BizException / 后端 code 字面值给用户
- 不能因为 LLM 不稳定而 retry > 3 次

== 错误友好文案对照表（必须实现） ==

| 后端事件 | 前端 UI 文案 |
|---|---|
| 403 ANSWER_DISABLED | "该知识库的应答模式已关闭，请联系管理员开启"（前端先拦截，不让用户提交）|
| 403 KB_ACCESS_DENIED | "您没有访问该知识库的权限" |
| 400 QUERY_REQUIRED | "请输入您的问题" |
| 400 KB_IDS_REQUIRED | "请至少选择一个知识库" |
| 422 PII_LEAK | "答案中检测到敏感信息，已停止显示。请联系管理员调整内容审核策略" |
| SSE error event NO_CITATIONS | 显示预设兜底答案"在当前知识库中没有找到与您问题相关的内容..." |
| 500 LLM 失败 | "智能应答服务暂时不可用，请稍后重试" |
| 网络中断 | "网络连接失败，请检查网络后重试" |
| EventSource 超时 | "应答超时，请重新提问" |

== 验收门槛 ==

1. 10 条全绿
2. a-acc-02 SSE 流式动效有录屏（架构师肉眼看 token 逐字出现）
3. a-acc-06 PII 拦截有截屏（友好提示证据）
4. a-acc-10 成本与性能数据贴 PR 描述
5. **错误文案断言至少 10 处通过**
6. PR 标题：test(answer-playground): playwright headed acceptance suite (10 cases)
7. PR 描述附 10 条用例 PASS/FAIL 表 + 错误文案截图

== 执行流程 ==

1. 输出"我将创建的关键文件清单"（≤12 个文件）
2. 跟架构师确认：
   - createKbWithAnswerOn 用 admin API 还是 SQL（默认 admin API）
   - a-acc-08 多 KB 选择 UI 是否已支持（不支持就 API 验证）
   - a-acc-09 "停止"按钮 UI 是否已实现（没实现就提需求）
3. 写 helper → 写 spec → 跑 --headed 看录屏
4. 全绿后 PR，附录屏 mp4 到 docs/answer-playground-acceptance/
```

`=== COPY END ===`

---

## 一次性总览

| 任务 | 用例数 | 关键验证点 |
|---|---|---|
| T8 数据清洗 | 10 | L1 规范化 / L2 去噪 / L3 PII MASK·HASH·REJECT / skipClean / 顺序 / 空内容 fail-fast / 性能 |
| T9 多策略分块 | 10 | 5 种 strategy 各自行为 / fallback / rechunk / AB lab UI / 表格保留 / 代码块完整性 / 性能 |
| T10 多模态 | 10 | 纯图 / 图文混合 PDF（核心）/ 跨模态检索 / 图搜图 / Word/HTML 内嵌图 / 损坏图 graceful / 成本 |
| T11 Answer-as-LLM | 10 | SSE 真流式 / 引用准确 / GuardRails / SSE PII MASK / answer_mode / 默认 qwen-plus / 跨 KB / 取消 / 成本 |
| **Q 质量看板** | **10** | **路由权限 / 空数据态颜色 / KPI 渲染 / 时间切换 / KB 筛选 / KB 切片 / 详情下钻 / 跨 KB 防泄漏 / 抽样设置 / Golden 回放** |
| **A 应答 Playground** | **10** | **表单 / SSE 真流式 / 引用卡片 / 跳转 / answer_mode=OFF 拦截 / PII 友好 / 空检索友好 / 跨 KB / 取消 / 成本** |

## 给你的执行建议

1. **不要并行**：T9 依赖 T8 PASS（chunker 上游是 cleaner），T11 依赖 T10 PASS（VL 向量空间）。Q 和 A 系列要等 T11 PASS。
2. **每个任务必须独立 PR**：失败时可以单独回滚，不污染其他任务进度。
3. **Codex 跑完每个任务先你肉眼看 headed 录屏**再 merge，特别是 T10-acc-02 图文混合 PDF + T11-acc-04 PII 拦截 + **q-acc-02 空数据态颜色 + a-acc-02 SSE 流式动效 + a-acc-06 PII 友好提示**。
4. **任何一条 FAIL**：让 Codex 区分是真 bug 还是 LLM 不稳定（重试 3 次还挂才算 bug）还是测试设计错。
5. **错误友好文案对照表**：Q 和 A 章节末尾各有一份对照表，**必须落地到 `frontend/src/api/error-messages.js` 集中维护**，不能散落在各组件里。这个 file 写完就 commit 一次（独立 PR）。

文件已写到：`docs/v5-acceptance-playwright-cases.md`，照章节复制即可。