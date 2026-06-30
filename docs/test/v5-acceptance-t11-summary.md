# T11 Answer-as-LLM Playwright E2E 验收测试总结

## 测试概览

本测试套件包含 **10 个测试文件，共 53 个测试用例**，覆盖 T11 Answer-as-LLM 功能的端到端验收验证。

## 测试结构

```
frontend/tests/e2e/v5-acceptance/t11/
├── _helpers/
│   └── t11-common.ts              # 测试辅助模块
├── fixtures/
│   ├── answer-kb-tech-faq.txt     # 100条技术FAQ
│   ├── answer-kb-pii-mixed.txt    # 含PII的文档
│   ├── answer-kb-multilingual.txt # 中英文混合FAQ
│   ├── answer-kb-numerical.txt    # 数值事实数据
│   ├── answer-kb-conflicting.txt  # 矛盾内容测试
│   └── answer-kb-empty.txt        # 空文档
├── t11-acc-01-streaming-token-by-token.spec.ts      # SSE流式验证 (2用例)
├── t11-acc-02-citation-link-to-chunk.spec.ts        # 引用链接验证 (5用例)
├── t11-acc-03-no-citation-guardrail.spec.ts         # 无引用拦截 (4用例)
├── t11-acc-04-pii-leak-guardrail.spec.ts            # PII泄露拦截 (4用例)
├── t11-acc-05-sse-retrieval-pii-masked.spec.ts      # SSE检索PII脱敏 (4用例)
├── t11-acc-06-answer-mode-off-blocks.spec.ts        # 禁用模式拦截 (5用例)
├── t11-acc-07-default-model-qwen-plus.spec.ts       # 默认模型验证 (5用例)
├── t11-acc-08-multi-kb-citation-merge.spec.ts       # 多KB引用合并 (5用例)
├── t11-acc-09-streaming-cancel.spec.ts              # 流式取消验证 (5用例)
└── t11-acc-10-perf-baseline-and-cost.spec.ts        # 性能基线 (14用例)
```

## 测试用例详细说明

### ACC-01: SSE 流式 Token-by-Token 验证 (2用例)
- **目标**: 验证 SSE 真实流式推送，前端答案区逐字出现
- **核心断言**: 
  - SSE 收到 ≥ 5 个 token event
  - 前端文本长度随时间递增
  - 最终答案含 "8080"
  - GuardRailResult = "PASS"

### ACC-02: 引用链接验证 (5用例)
- **目标**: 验证 [n] 引用格式和点击跳转功能
- **核心断言**:
  - 答案含至少 1 个 [n] 格式引用
  - 引用区显示对应 chunk snippet
  - 点击跳转到 DocumentDetail 页
  - retrieval event 在 token event 之前到达

### ACC-03: 无引用 GuardRail 拦截 (4用例)
- **目标**: 空 KB 时返回 "未找到相关信息" 并正确拦截
- **核心断言**:
  - 答案是预设 NOT_FOUND_ANSWER 字符串
  - response.citations 是空数组
  - GuardRailResult ∈ {PASS, NO_CITATIONS}
  - SSE 收到 complete event

### ACC-04: PII 泄露 GuardRail 拦截 (4用例) - **核心安全用例**
- **目标**: LLM 答案含 PII 时必须被拦截或脱敏
- **核心断言**:
  - 二选一: a) 无原始 PII (已脱敏) 或 b) GuardRailResult = "PII_LEAK"
  - 禁止: 答案含原始手机号 + GuardRailResult=PASS
- **测试数据**: 含手机号 138xxx、邮箱、身份证号的文档

### ACC-05: SSE Retrieval PII 脱敏 (4用例) - **核心安全用例**
- **目标**: SSE retrieval event 中的 chunk content 必须经过 PII MASK
- **核心断言**:
  - retrieval.chunks[i].content 不含原始 PII (regex 验证)
  - 含掩码占位 (138****5678 或类似)
  - chunkId / docId / scores 等元数据完整保留

### ACC-06: Answer Mode OFF 拦截 (5用例)
- **目标**: KB.answer_mode='OFF' 时调用返回 403
- **核心断言**:
  - HTTP 403 或 SSE error event code=ANSWER_DISABLED
  - 前端显示 "该 KB 未启用应答模式"
  - answer_logs 表无新增记录

### ACC-07: 默认模型 qwen-plus 验证 (5用例)
- **目标**: KB 未显式设 answer_model 时默认走 qwen-plus
- **核心断言**:
  - answer_logs.llm_model = 'qwen-plus'
  - 不能是 'qwen-max' 或其他
  - latency.llm > 0

### ACC-08: 多 KB 引用合并 (5用例)
- **目标**: 跨多个 KB 时引用正确归属
- **核心断言**:
  - 答案含分别来自 A 和 B 的引用
  - answer_logs.kb_ids_csv = "<idA>,<idB>"
  - citations 中 docId 分布在两个 KB

### ACC-09: 流式取消验证 (5用例) - **核心安全用例**
- **目标**: 前端取消 SSE 后后端及时停止 LLM 调用
- **核心断言**:
  - 收到的 token 长度 < 完整答案长度
  - 后端日志含 "SSE_CLIENT_DISCONNECTED"
  - answer_logs 有部分答案记录
  - 不会因为客户端断开而 500

### ACC-10: 性能基线和成本 (14用例)
- **目标**: 观测端到端延迟、token 用量、引用数和成本
- **核心断言**:
  - 平均 total_latency_ms < 5000
  - 平均 prompt_tokens < 2000
  - 平均 citations 数 ∈ [1, 5]
  - citation 利用率 ≥ 0.4
  - 10 次累计 DashScope 成本 ≤ ¥1

## 运行命令

```bash
# Headed 模式运行（推荐用于调试）
npx playwright test tests/e2e/v5-acceptance/t11 --headed --workers=1

# 仅运行特定测试文件
npx playwright test tests/e2e/v5-acceptance/t11/t11-acc-04-pii-leak-guardrail.spec.ts --headed

# CI 模式运行
npx playwright test tests/e2e/v5-acceptance/t11 --workers=1
```

## 环境要求

- PostgreSQL 可访问 (psql)
- 后端服务运行 (默认 https://ragforge.net)
- DashScope API 可用 (qwen-plus)
- 使用真实 LLM，不使用 mock

## 核心安全用例

根据任务要求，以下 3 个用例必须各附录屏:

1. **t11-acc-04-pii-leak-guardrail.spec.ts** - PII 泄露拦截
2. **t11-acc-05-sse-retrieval-pii-masked.spec.ts** - SSE 检索 PII 脱敏  
3. **t11-acc-09-streaming-cancel.spec.ts** - 流式取消

## 输出位置

- **Trace 归档**: `frontend/test-results/v5-acceptance/t11/`
- **性能报告**: `frontend/test-results/v5-acceptance/t11/t11-acc-10-perf-report.json`
- **截图**: `test-results/v5-acceptance/t11/*.png`

## 验收门槛

1. 10 条测试文件全绿
2. 3 条核心安全用例附录屏
3. t11-acc-10 成本报告 + 性能基线表附 PR
4. PR 标题: `test(v5/T11): playwright headed answer-as-llm acceptance (10 cases)`
5. 完成后在 `docs/v5-execution-tasks.md` 追加: `- T11 验收 ✅ <commit-sha> 2026-MM-DD（10/10 PASS）`
