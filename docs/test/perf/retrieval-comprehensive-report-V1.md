# RAGForge 检索链路全面测试报告（多模态/多格式 + 质量 + QPS + 瓶颈）

- 拟制日期：2026-07-04  版本：V1
- 环境：生产集群内网直压（app 节点 228 → localhost:31090，绕开跨境网络），向量后端 Qdrant（1024维/INT8）
- 数据：KB 643（18 个多格式多模态文档）用于覆盖+质量；KB 638（400 文档/1765 chunk）用于 QPS
- 被测版本：含 Qdrant 迁移、图片/docx 支持、并发调优、MQ afterCommit 等全部修复

## 0. 一句话结论
**检索质量优秀**（成功入库的独立内容 recall@10=100%，full/rerank 排序最好 MRR 0.94）；**检索链路的性能瓶颈是 DashScope query-embedding 调用（占单请求 90%+ 延迟，~190ms 基线、并发下涨到 458ms）——不是 Qdrant（8-59ms）也不是硬件（CPU 空闲）**。keyword 无 embedding 故 QPS 最高（~177）。另发现 3 个待修项：纯图片PDF入库失败、zip 直传被拒、topK 边界英文报错不友好。

## 1. 多格式 / 多模态入库覆盖
| 格式/模态 | 结果 |
|---|---|
| txt / md / csv / html / pdf(文本) / **docx** | ✅ COMPLETED，TEXT chunk（docx 修复后正常） |
| png / jpg（纯图片，接口直传） | ✅ COMPLETED，IMAGE chunk（OCR 提取图上文字） |
| 图文混合 pdf（文字+内嵌图） | ✅ 产生 TEXT + IMAGE 两类 chunk |
| **纯图片 pdf（图为主体）** | ❌ FAILED「文档无法分块」——无文字走文本管道失败（待修） |
| **zip 压缩包（直传）** | ❌ 400 拒绝——不在直传白名单，需走 presign（待修/对齐） |

模态分布：IMAGE 3 / TEXT 14（16 个文档成功）。

## 2. 检索质量（五策略，recall@10 / MRR）
固定 query 集（每文档一个自然语言查询），检查目标文档是否在 top-10：

| 策略 | 整体 recall@10 | 文本 | 图片 | MRR |
|------|---------------|------|------|-----|
| keyword | 94% (16/17) | 13/13 | 3/4 | 0.88 |
| vector | 94% | 13/13 | 3/4 | 0.91 |
| hybrid | 94% | 13/13 | 3/4 | 0.91 |
| rewrite | 94% | 13/13 | 3/4 | 0.91 |
| full | 94% | 13/13 | 3/4 | **0.94** |

- **文本 100% 召回**（所有文本格式可检索）；图片 3/4，唯一 miss 是 docker_img.pdf **入库失败**（非检索问题）。
- **full（改写+混合+rerank）排序最优（MRR 0.94）**，keyword 排序略弱（0.88）。独立内容下召回都满，差距体现在排序。
- 补充：难区分场景（400 同质语料）hybrid 优于单一 vector（历史测得 hybrid 16.7% > keyword 13.3% > vector 6.7%）。

## 3. QPS / 并发性能（KB 638，并发调优后）
| 策略 | 单请求延迟 | 最大干净并发 / QPS | 超限行为 |
|------|-----------|-------------------|----------|
| keyword | ~30ms | c=80 / **~177 QPS** | 干净 429 |
| vector | ~200ms | c=16 / **~33 QPS**（调优前 c=6→429） | c=24 起 429 |
| hybrid | ~240ms | c=10 / **~40 QPS**（调优前 c=10→504） | c=20 仍 504（超时缩到5s） |
| rewrite | ~800ms | c=2 / ~2.2 QPS | 429（限流3） |
| full | ~950ms | c=1 / ~1 QPS | 429（限流1） |

## 4. 瓶颈定位（核心）
服务端延迟分解（vector 单请求日志）：
```
embedLatency=189~458ms   qdrantLatency=8~59ms   totalLatency=199~521ms
```
- **瓶颈 = DashScope query-embedding 调用**：占单请求 90%+ 延迟；并发升高时从 189ms 涨到 458ms（DashScope 排队），这是 vector/hybrid QPS 卡在 ~33-40 的根因。
- **Qdrant 向量检索仅 8-59ms**，PG 回捞正文快，**CPU/内存空闲**——都不是瓶颈。
- **keyword 无 embedding** → 直接 ES BM25，QPS 最高（~177）。
- **rewrite/full 叠加 LLM**（改写 qwen-turbo / rerank qwen3-rerank）→ 天然低 QPS（2/1）。
- hybrid 限流放到 20 但 c=20 仍 504：执行器/embedding 在高并发下排队；c=10 已干净。

**提升检索 QPS 的方向**：① query embedding 加语义缓存（复用相同/近似查询的向量，直接砍掉最大头延迟）；② embedding 客户端连接池优化；③ 硬件/Qdrant 无需动。

## 5. 异常 / 边界 / 安全
| 用例 | 结果 | 评估 |
|------|------|------|
| 空 query | 400「请输入检索内容」 | ✅ 友好 |
| 超长 query（9000字） | 200 正常 | ✅ 稳健 |
| **topK=0 / -1 / 100000** | 400 **英文** "must be greater/less than..." | ❌ **不友好(bug)** + 泄露上限 50 |
| 越权 kb（别人的 374） | 200 空结果 | ✅ ACL 过滤，无跨用户泄露 |
| 不存在的 kb | 200 空 | 可接受（静默） |
| SQL 注入字符串 | 200 正常 | ✅ 参数化安全 |
| emoji / 换行 / 特殊字符 | 200 正常 | ✅ |
| 无效策略 foobar | 200（默认 vector） | 容错（可考虑显式拒绝） |

## 6. 待修清单（按优先级）
| 优先级 | 问题 | 建议 |
|------|------|------|
| 高 | **topK 边界返回英文 Bean-Validation 消息**（不友好+泄露上限） | 换成友好中文，如「topK 需在 1~50 之间」 |
| 中 | **纯图片 PDF 入库 FAILED** | 无文字的 PDF 应回退到图片管道（提取内嵌图 OCR），而非文本管道直接失败 |
| 中 | **zip 直传 400**（前端 presign 可、接口直传不可） | 接口 ALLOWED_EXTENSIONS 对齐（同图片修复），或明确返回「压缩包请走…」 |
| 低 | 无效策略静默默认 vector | 可显式拒绝无效 strategy |

## 7. 复现
- 语料生成：`gen_comprehensive.py`（多格式多模态 + query 映射）
- 压测：`rag_loadtest.py search --strategy <s> --ladder ... --kb 638`
- 质量/边界：见本目录脚本；数据保留于 KB 638/643（测后未删）
