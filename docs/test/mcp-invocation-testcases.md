# RAGForge MCP 调用 — 专项测试用例集

> 目标:全面验证 MCP 是否**真实可用、无 bug、能深挖隐藏缺陷**。共 **80+ 用例**,每模块 ≥6。
> 依据代码:`StreamableMcpController`(/mcp,JSON-RPC streamable)、`McpServerConfig`+`RagForgeMcpTools`(/sse,Spring AI)、`ApiKeyInterceptor`(鉴权)。
> 执行方式:REST/JSON-RPC 直连(curl/HTTP)为主 + 少量 MCP 客户端(Claude/Cursor)端到端。**本文只设计,不执行。**

## 约定与环境

- 端点:`POST https://<host>/mcp`(JSON-RPC 2.0);Spring AI SSE `/sse`。均经 `ApiKeyInterceptor`(白名单外)。
- 认证:请求头 `X-API-Key: <key>`。
- 前置 key(见 API Key 用例集):
  - `KEY_ORGALL_A`:组织 A、ORG_ALL、READ、有效
  - `KEY_KBLIST_A`:组织 A、KB_LIST=[kA1]、READ
  - `KEY_ORGALL_B`:组织 B、ORG_ALL、READ
  - `KEY_EXPIRED`:已过期;`KEY_DISABLED`:enabled=false
- 前置数据:组织 A 有库 kA1(有内容)、kA2;组织 B 有库 kB1;另有 SYSTEM 库 kSys。
- 断言维度:HTTP 状态、JSON-RPC 结构(jsonrpc/id/result/error)、`content[].text`、`isError`、命中库范围、隔离性、错误友好性、不 500/不崩。
- 工具名现状:`/mcp` 暴露 `list_knowledge_bases`、`search_knowledge`(tools/call 兼容驼峰别名 `listKnowledgeBases`/`searchKnowledgeBase`);`/sse` 暴露 `search_knowledge`、`listKnowledgeBases`、`answer_with_citations`。

---

## M1. JSON-RPC 协议与传输（/mcp）

| ID | 用例 | 步骤 | 预期 | 优先级 |
|---|---|---|---|---|
| MCP-01 | initialize 基本返回 | 有效 key,`method=initialize` | result 含 protocolVersion、capabilities.tools.listChanged=false、serverInfo(name=ragforge-mcp-server,version=1.0.0)、instructions 非空 | P0 |
| MCP-02 | protocolVersion 回显 | params.protocolVersion="2025-03-26" | result.protocolVersion 原样回显 | P1 |
| MCP-03 | protocolVersion 缺省 | 不传 protocolVersion | 回默认 "2025-03-26" | P1 |
| MCP-04 | tools/list 结构 | method=tools/list | jsonrpc="2.0" + 原 id + result.tools 为数组 | P0 |
| MCP-05 | tools/call 结构 | 合法 call | result.content[0].type="text";isError 字段存在 | P0 |
| MCP-06 | ping | method=ping | result={} | P2 |
| MCP-07 | 未知 method | method=foo/bar | error.code=-32601,message 含 "Method not found" | P1 |
| MCP-08 | notification(无 id) | 请求不带 id | HTTP 202 Accepted,无响应体(不回 result) | P1 |
| MCP-09 | id 类型保持(数字) | id=1(number) | 响应 id=1 且为数字类型 | P2 |
| MCP-10 | id 类型保持(字符串) | id="abc" | 响应 id="abc" | P2 |
| MCP-11 | jsonrpc 恒为 2.0 | 任意请求 | 响应 jsonrpc 恒="2.0"(即使请求未带) | P2 |
| MCP-12 | Content-Type 限定 | 非 application/json 提交 | 415(consumes 限定)或合理拒绝 | P2 |
| MCP-13 | 非法 JSON body | 发送坏 JSON | 400,不 500、不崩 | P1 |
| MCP-14 | params 缺失不 NPE | tools/call 无 params | params() 返回空 map;走"Unknown tool: null"或参数缺失,不 500 | P1 |
| MCP-15 | 无状态性 | 连续多请求不带 session | 各自独立处理,无跨请求状态残留 | P2 |
| MCP-16 | GET /mcp | 用 GET 访问 | 405 Method Not Allowed | P2 |

## M2. 鉴权（X-API-Key on /mcp 与 /sse）

| ID | 用例 | 步骤 | 预期 | 优先级 |
|---|---|---|---|---|
| MCP-17 | 无 key | 不带 X-API-Key,tools/list | 401 | P0 |
| MCP-18 | 空 key | X-API-Key: (空) | 401 | P1 |
| MCP-19 | 伪造/乱码 key | 随机串 | 401,不泄漏细节 | P0 |
| MCP-20 | 禁用 key | KEY_DISABLED | 401 | P0 |
| MCP-21 | 过期 key | KEY_EXPIRED | 401,"API Key expired" | P0 |
| MCP-22 | 有效 key 通过 | KEY_ORGALL_A initialize | 200 正常 | P0 |
| MCP-23 | hash 优先命中 | 新建 key(有 key_hash)调用 | 通过(按 SHA-256 命中) | P1 |
| MCP-24 | 明文回退命中 | 存量无 hash 的 key | 通过(回退明文) | P1 |
| MCP-25 | dev key(仅 dev) | sk-ragforge-dev(dev profile) | dev 环境通过;prod 无效 | P2 |
| MCP-26 | 限流超阈 | 同 key 高频调用超 rateLimit | 429 | P1 |
| MCP-27 | 限流 fail-closed | 模拟 Redis 不可用 | 拒绝(不放行) | P1 |
| MCP-28 | org 上下文来自 key | 不传 X-Org-Id 调用 | 以 key.orgId 为组织上下文(隔离生效) | P0 |
| MCP-29 | 传 X-Org-Id 试图越权 | 手动带他组织 X-Org-Id + key | 仍以 key 绑定组织为准,不被外部头提权 | P0 安全 |

## M3. tools/list 与两入口一致性

| ID | 用例 | 步骤 | 预期 | 优先级 |
|---|---|---|---|---|
| MCP-30 | /mcp 工具集 | tools/list | 含 list_knowledge_bases + search_knowledge,各有 name/description/inputSchema | P0 |
| MCP-31 | search inputSchema | 查 search_knowledge schema | required=["query"];properties 含 query/kbIds/topK,类型正确 | P1 |
| MCP-32 | list inputSchema | 查 list_knowledge_bases | inputSchema.type=object,properties 空对象 | P2 |
| MCP-33 | /sse 工具集 | Spring AI tools/list | 含 search_knowledge / listKnowledgeBases / answer_with_citations(3 个) | P0 |
| MCP-34 | 两入口工具名差异 | 对比 /mcp 与 /sse | 记录差异:/mcp=snake_case 两工具(无 answer);/sse=3 工具且 list 为驼峰 listKnowledgeBases、answer 为 answer_with_citations。**核对前端展示与文档是否与实际一致** | P1 |
| MCP-35 | 工具描述语义 | 读 description/instructions | 引导"先 list 再 search"、"仅在用户明确要求时搜私有库" | P2 |
| MCP-36 | 前端 MCP 工具卡片一致 | 开发者中心工具列表 vs 实际 | 展示的工具名与实际可调用名一致(防"展示了调不通的工具") | P1 |

## M4. search_knowledge 工具

| ID | 用例 | 步骤 | 预期 | 优先级 |
|---|---|---|---|---|
| MCP-37 | 正常检索 | call search_knowledge {query:"Java 高并发"} | 返回"找到 N 条相关内容"含来源;或"未找到相关内容(query=…)" | P0 |
| MCP-38 | query 缺失 | 不传 query | isError=true,"Missing required argument: query" | P0 |
| MCP-39 | query 空/空白 | query:" " | 同上 isError | P1 |
| MCP-40 | kbIds 字符串 | kbIds:"kA1,kA2" | 过滤为可读子集后检索 | P1 |
| MCP-41 | kbIds 数组 | kbIds:[kA1,kA2] | normalizeKbIds 转逗号串,正常 | P1 |
| MCP-42 | kbIds 不传 | 省略 kbIds | 用 allReadableKbIds() 全量可读 | P1 |
| MCP-43 | topK 缺省 | 不传 topK | 默认 5 | P2 |
| MCP-44 | topK 超上限 | topK:999 | clamp 到 10 | P1 |
| MCP-45 | topK<=0 | topK:0 / -3 | 回落为 5(内部 topK<=0?5) | P2 |
| MCP-46 | topK 非法字符串 | topK:"x" | intValue 回退 5,不崩 | P2 |
| MCP-47 | 无可读库 | 用无授权库的 key | 返回"没有可访问的知识库。" | P0 |
| MCP-48 | 传他组织 kbId | KEY_ORGALL_A 传 kB1 | 被 filterReadable 过滤;全无权→"没有可访问的知识库" | P0 安全 |
| MCP-49 | 检索后端异常 | 模拟检索超时/降级 | 捕获→"搜索失败：…",不抛 500、不崩 | P1 |
| MCP-50 | 结果含来源 | 正常检索 | 结果片段带"来源：<filename>" | P2 |

## M5. list_knowledge_bases 工具

| ID | 用例 | 步骤 | 预期 | 优先级 |
|---|---|---|---|---|
| MCP-51 | 正常列出 | call list_knowledge_bases | "可用知识库列表"含 ID/名称/文档数/片段数(有描述则附) | P0 |
| MCP-52 | 无可读库 | 无授权库 key | "当前没有可用的知识库。" | P1 |
| MCP-53 | 驼峰别名 | call listKnowledgeBases | 与 snake_case 同效(callTool 双名) | P2 |
| MCP-54 | 仅本组织库 | KEY_ORGALL_A | 只列 A 的库,不含 B 的 kB1 | P0 安全 |
| MCP-55 | 不含 SYSTEM 库 | 有 kSys 时 | 列表不含 SYSTEM 库 | P1 |
| MCP-56 | 列表异常 | 模拟 DB 异常 | "获取知识库列表失败：…",不崩 | P2 |
| MCP-57 | KB_LIST key 的列表范围 | KEY_KBLIST_A | 列表仅含被授权/可读的库(与 allReadableKbIds 口径一致) | P1 |

## M6. answer_with_citations（/sse Spring AI）

| ID | 用例 | 步骤 | 预期 | 优先级 |
|---|---|---|---|---|
| MCP-58 | 正常应答 | answer {query, kbIds:[kA1]} | 返回带引用答案(含图片 URL 结构) | P1 |
| MCP-59 | 无 kbIds | 省略 kbIds | 用 allReadableKbIds | P2 |
| MCP-60 | 全无权 kbIds | 传 kB1(A 的 key) | 抛"没有可访问的知识库"(IllegalArgumentException) | P0 安全 |
| MCP-61 | 检索策略 | 查请求 | hybrid、topK=10、maxTokens=800、非流式 | P2 |
| MCP-62 | /mcp 不暴露 answer | 在 /mcp call answer_with_citations | "Unknown tool"(该端点无 answer 分支) | P1 |
| MCP-63 | answer 无可读库时不越权 | 无授权 | 拒绝,不返回他组织内容 | P0 安全 |

## M7. 组织 / scope 隔离（核心安全）

| ID | 用例 | 步骤 | 预期 | 优先级 |
|---|---|---|---|---|
| MCP-64 | ORG_ALL 覆盖本组织 | KEY_ORGALL_A search/list | 覆盖 A 全部可读库 | P0 |
| MCP-65 | KB_LIST 仅授权库 | KEY_KBLIST_A search 传 [kA1,kA2] | 仅 kA1 命中(kA2 未授权被过滤) | P0 安全 |
| MCP-66 | 跨组织读不到(search) | KEY_ORGALL_A 检索 B 内容 | 不命中 B 的库 | P0 安全 |
| MCP-67 | 跨组织读不到(list) | KEY_ORGALL_A list | 不含 B 的库 | P0 安全 |
| MCP-68 | 移除 PUBLIC 后隔离 | 批次3 后,他组织原公开库 | key 读不到(无全域公开放行) | P0 安全 |
| MCP-69 | 不同 org 的 key 隔离 | KEY_ORGALL_A vs KEY_ORGALL_B 同 query | 各自只返回本组织结果,互不可见 | P0 安全 |
| MCP-70 | SYSTEM 库不可达 | search 命中 SYSTEM? | SYSTEM 库永不出现在结果/列表 | P1 安全 |
| MCP-71 | READ key 无写副作用 | MCP 全流程 | 仅读;无写接口/无数据变更 | P1 安全 |
| MCP-72 | REST 与 MCP 同源隔离 | 同 key 调 /search 与 MCP search | 可读范围一致(共用 KbAccessGuard) | P1 |
| MCP-73 | 吊销后立即失效 | 调用中吊销该 key | 后续 MCP 调用 401 | P0 安全 |

## M8. 深挖 bug / 边界 / 安全

| ID | 用例 | 步骤 | 预期 | 优先级 |
|---|---|---|---|---|
| MCP-74 | kbIds 含非数字 | search kbIds:"abc" | parseKbIds 抛 NumberFormatException 被 catch→"搜索失败：…",**不 500**(重点核实) | P0 深挖 |
| MCP-75 | kbIds 含负数/超大数 | kbIds:"-1,99999999999" | 过滤后无权→空结果或"没有可访问的知识库",不崩 | P1 |
| MCP-76 | query 超长 | query 10k+ 字符 | 正常处理或合理限制,不崩、不超时雪崩 | P1 |
| MCP-77 | query 注入尝试 | query 含 SQL/prompt 注入片段 | 安全:无 SQL 注入、无越权;按普通检索处理 | P0 安全 |
| MCP-78 | arguments 缺失 | search 无 arguments | map() 空→query 缺失→isError,不 500 | P1 |
| MCP-79 | name 缺失 | tools/call 无 name | "Unknown tool: null",不崩 | P2 |
| MCP-80 | topK 浮点 | topK:5.9 | intValue 取整=5,不崩 | P2 |
| MCP-81 | 并发同 key | 并发 N 个 search | 限流计数正确、结果不串、无竞态 | P1 |
| MCP-82 | 超大 topK 边界 | topK:2147483648(超 int) | 解析回退/ clamp,不溢出崩溃 | P2 深挖 |
| MCP-83 | protocolVersion 注入 | protocolVersion 传超长/特殊串 | 原样回显于 JSON(确认无注入/污染下游) | P2 |
| MCP-84 | 大量 kbIds | kbIds 传 500 个 id | 过滤/查询不崩,性能可接受 | P2 |
| MCP-85 | 响应体符合 MCP 规范 | 各 tools/call | content[].type/text 结构、isError 语义符合 MCP 客户端预期(Claude/Cursor 能解析) | P1 |
| MCP-86 | 端到端真实客户端 | Claude/Cursor 配 mcpUrl+X-API-Key | 能发现工具、调用 search 返回结果(真实可用验证) | P0 |

## M9. 选定范围硬边界 —— KB_LIST 绝不越界（核心）

> 主诉求:一把 key 只能访问它被授权的知识库,**无论调用方传什么参数都不能超出**。前置 `KEY_KBLIST_A` 授权=[kA1];组织 A 另有 kA2、kA3;组织 B 有 kB1。

| ID | 用例 | 步骤 | 预期 | 优先级 |
|---|---|---|---|---|
| MCP-87 | 不传 kbIds 也被限定 | KEY_KBLIST_A search 省略 kbIds | **只查 kA1**,绝不因"未指定"而扩到 kA2/kA3(默认=授权集,非全组织) | P0 安全 |
| MCP-88 | 传授权外同组织库被过滤 | search kbIds:[kA1,kA2] | 只命中 kA1;kA2(同组织但未授权)被剔除 | P0 安全 |
| MCP-89 | 只传授权外库→空 | search kbIds:[kA2] | 返回"没有可访问的知识库。",**不回退到全部**(防"传非法 id 反而查全部"的越界 bug) | P0 安全 |
| MCP-90 | 传他组织库被过滤 | search kbIds:[kB1] | 剔除→"没有可访问的知识库。" | P0 安全 |
| MCP-91 | 混合授权+越权 id | search kbIds:[kA1,kA2,kB1] | 仅 kA1 命中,其余全滤掉 | P0 安全 |
| MCP-92 | list 只列授权库 | KEY_KBLIST_A list_knowledge_bases | 只列 kA1,不含 kA2/kA3/kB1 | P0 安全 |
| MCP-93 | answer 也受限授权集 | /sse answer 不传 kbIds(KB_LIST key) | 仅在 kA1 内应答;传 kA2→过滤后无权则拒 | P0 安全 |
| MCP-94 | 新增库不自动进授权集 | key 创建后,组织 A 新增 kA3 | KB_LIST key **看不到 kA3**(白名单在创建时固定,不随组织新增而扩) | P0 安全 |
| MCP-95 | 授权库被删除 | kA1 被删除后 | 该 key 查询 kA1 → 不命中/空,不报 500 | P1 |
| MCP-96 | 授权库被迁到他组织 | kA1 的 org 改为 B 后 | 隔离生效:按当前隔离规则不越权读到(核实 allowed_kb_ids 与库归属变化的交互) | P1 深挖 |
| MCP-97 | ORG_ALL 也不越组织 | KEY_ORGALL_A 不传 kbIds | 覆盖 A 全部可读库,但**绝不含 B/SYSTEM** | P0 安全 |
| MCP-98 | ORG_ALL 传他组织 id | KEY_ORGALL_A kbIds:[kB1] | 过滤→空;不越权 | P0 安全 |
| MCP-99 | 越界不静默成功 | 传纯越权 id 集合 | 明确返回"没有可访问的知识库"而非返回他库内容;审计/日志可追(KB_ACCESS_DENIED 指标) | P1 安全 |
| MCP-100 | REST 与 MCP 边界一致 | 同 KB_LIST key 调 /search 与 MCP,传越权 id | 两者过滤口径一致(共用 filterReadable) | P1 |

## M10. Key 过期 / 失效 / 生命周期边界

| ID | 用例 | 步骤 | 预期 | 优先级 |
|---|---|---|---|---|
| MCP-101 | 过期 key 全方法拒 | KEY_EXPIRED 调 initialize / tools/list / tools/call | 均 401 "API Key expired"(不只 call 被拦) | P0 安全 |
| MCP-102 | 过期临界(刚过) | expires_at = 当前-1s | 拒绝 | P1 |
| MCP-103 | 过期临界(将过) | expires_at = 当前+1min,T+2min 调用 | T+2min 时拒绝 | P1 |
| MCP-104 | 永不过期 | expiresAt=null 的 key | 长期有效,不被误判过期 | P1 |
| MCP-105 | 禁用后即失效 | 运行中 enable=false | 后续调用 401 | P0 安全 |
| MCP-106 | 吊销(治理)后即失效 | 超管破玻璃 revoke | 后续 MCP 调用 401 | P0 安全 |
| MCP-107 | 过期+范围叠加 | 过期的 KB_LIST key | 先过期拦截(401),不进入范围判断 | P2 |
| MCP-108 | 删除 key 后失效 | delete key | 401 | P1 |
| MCP-109 | 时区/时钟一致 | 过期判定用服务器时间 | 与客户端时区无关,按服务端 LocalDateTime 判 | P2 深挖 |
| MCP-110 | 过期后仍不泄漏工具 | 过期 key tools/list | 401,不返回工具清单(不泄漏能力面) | P1 安全 |
| MCP-111 | 明文/hash 双通道过期一致 | 过期的老明文 key 与新 hash key | 两种命中路径都执行过期校验 | P1 |
| MCP-112 | 限流与过期顺序 | 过期 + 高频 | 过期优先返回 401(在限流之后但优先于业务),行为确定 | P2 |

---

## 执行与验收

- 覆盖:**10 模块共 112 用例**;每模块 ≥6;含协议/鉴权/工具/隔离/**选定范围硬边界**/**过期与生命周期**/深挖 bug/端到端。
- **核心红线(必须全绿)**:
  1. **绝不越界**(M9):KB_LIST key 无论传什么参数(不传/传同组织未授权库/传他组织库/混合/纯越权)都**只能命中授权集**;传纯越权 id **不得回退到全部**(MCP-89);新增库不自动进授权集(MCP-94)。
  2. **过期即不可用**(M10):过期/禁用/吊销/删除的 key 对 initialize/tools/list/tools/call **全部 401**,且不泄漏工具清单(MCP-110)。
- 通过标准:所有 P0/P1 通过;**跨组织/SYSTEM/PUBLIC/授权外库 隔离零泄漏**;非法输入(MCP-74/77/78/82)**一律不 500、不崩、错误友好**;真实客户端(MCP-86)能发现并调通工具。
- 深挖重点(易藏 bug):`kbIds="abc"` 的 NumberFormatException 路径(MCP-74)、外部 X-Org-Id 提权(MCP-29)、两入口工具名不一致导致"展示能调实际调不通"(MCP-34/36)、`/mcp` 不含 answer 而前端可能展示(MCP-62)。
- 建议:M1-M5/M7/M8 用 curl JSON-RPC 直连断言;M6 走 /sse;MCP-86 用真实 Claude/Cursor 客户端端到端。
- 本用例集针对当前 `main` 实现;工具名/入口差异(MCP-34)属现状需产品确认的一致性问题,应在实现前标"预期红"、对齐后转"绿"。

编制 2026-07-02 · 只设计不执行
