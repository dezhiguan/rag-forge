# RAGForge 检索链路测试报告 V2（质量 + QPS + 缓存优化 + 瓶颈演进）

- 拟制日期：2026-07-04  版本：V2（在 [V1](retrieval-comprehensive-report-V1.md) 基础上：更难的判别性质量集 + query/rewrite/rerank 三层缓存 + 并发调优后的复测）
- 环境：生产集群**内网直压**（app 节点 228 → localhost:31090，绕开跨境网络），向量后端 Qdrant（1024 维 / INT8）
- 数据：KB 644（判别性语料，质量）、KB 638（400 文档 / 1765 chunk，QPS）
- 本版新增能力：query 向量缓存、rerank 结果缓存（rewrite 结果缓存原已存在）、vector/hybrid 并发调优

## 0. 一句话结论
质量上，**语义/改写查询才是策略分水岭**：vector/full 最优、keyword 最差、hybrid 被弱 keyword 臂拖累。性能上，检索最大头延迟是 **DashScope query-embedding**（占 90%+）；引入 **query 向量缓存**后，热点查询单请求从 ~200ms 降到 **~15ms**，配合并发调优，**vector 34→~200 QPS、hybrid 21→~169 QPS、full 7→~14 QPS**（均 0 错误）。瓶颈随之从"embedding"转移到"真实吞吐（Qdrant/PG/CPU）"。缓存不影响 token 计量（计量在模型调用内部，命中不记幻影 token）。

## 1. 检索质量（五策略）
判别性语料（5 簇语义相近文档，如 Redis 的持久化/集群/过期/pipeline 等）。

**精确关键词查询**（query 含目标原词）：五策略全 R@1=100%——内容可分时召回不构成区分。

**语义改写查询**（query 不含原词，纯靠语义）——策略分水岭：
| 策略 | R@1 | R@3 | R@10 | MRR |
|------|-----|-----|------|-----|
| **vector / full** | **85%** | 100% | 100% | **0.925** |
| rewrite | 85% | 100% | 100% | 0.917 |
| hybrid | 75% | 85% | 100% | 0.821 |
| **keyword** | 75% | 80% | **85%** | **0.782** |

- **keyword 最差**：BM25 匹配不上改写，15% 目标连 top-10 都进不去。
- **vector/rewrite/full 最优**：语义 embedding 扛得住改写。
- **hybrid 反被弱 keyword 臂拖累**：RRF 融合稀释了 vector 的好排序，纯语义查询下 hybrid < vector。
- **选型**：自然语言/语义查询 → vector 或 full；含精确术语/代号 → hybrid；要吞吐且接受字面匹配 → keyword。

## 2. 性能瓶颈（原始）
vector 单请求延迟分解：`embedLatency 150~490ms / qdrantLatency 5~62ms / total 157~555ms`
→ **DashScope query-embedding 占单请求 90%+**；Qdrant 仅 5-62ms；CPU/内存有余量。keyword 无 embedding 故最快。rewrite/full 另叠加 LLM 改写 / rerank。

## 3. 优化措施（本版落地）
| 优化 | 说明 | 计量安全 |
|------|------|---------|
| **query 向量缓存** | Caffeine（容量 2 万 / TTL 15min / 带开关），命中跳过 embedding | 计量在 DashScope client 内部，命中不记 token |
| **rewrite 结果缓存** | `@Cacheable(queryRewrite)`（原已存在，Caffeine 500/1h），命中跳过 LLM 改写 | 同上 |
| **rerank 结果缓存** | `@Cacheable(rerankResult)`，key=query+候选内容+topN；候选集变即失效不 stale | 同上 |
| **并发调优** | vector 16→48、hybrid 20→32、执行器 core8→12/max48→64 | — |

**计量口径**：token 输入/输出统计均记在**真正的模型调用内部**（embedding/rewrite/rerank 各自 client 的 `modelUsageRecorder.record`），缓存在其上层。**命中即不进模型调用 → 不记 token**，统计如实反映真实消耗与成本下降，无幻影计数。

## 4. 缓存效果（vector 单请求，热点命中）
| 指标 | 缓存前（冷/唯一查询） | 缓存后（命中） |
|------|---------------------|---------------|
| embedLatency | 150~490ms | **0ms** |
| 单请求总延迟 | ~200~500ms | **~15ms**（仅 Qdrant） |
| p50 | ~200ms | **76ms** |

日志实证：命中时 `embedLatency=0ms qdrantLatency=12~17ms`。

## 5. QPS / 并发（优化前后，热点查询，均 0 错误）
| 策略 | 最初（无缓存·旧限流） | 现在（缓存 + 新限流） | 提升 |
|------|---------------------|---------------------|------|
| **vector** | ~34 QPS | **~180-240 QPS**（c=48-96） | ~6-7× |
| **hybrid** | ~21 QPS（带 504 尾） | **~169 QPS**（c=32） | ~8× |
| **full** | ~7 QPS / p50 550ms | **~14 QPS / p50 286ms** | ~2× |
| keyword | ~164 QPS | ~164 QPS（缓存无关，ES-bound） | — |

- vector：限流器 16→48 后不再人为卡；此前"290+"含 429 快速失败，现为全成功真实吞吐。
- hybrid：执行器扩容 + 缓存把 504 尾治好，c=32 稳跑 169 QPS。
- full：embedding + rewrite + rerank 三层缓存复合，p50 减半、QPS 翻倍。

## 6. 瓶颈演进（核心）
- **优化前**：vector/hybrid 卡在 **DashScope query-embedding**（150-490ms）。
- **优化后（热点）**：embedding 归零，限流器放开，瓶颈转移到**真实吞吐**（Qdrant ANN + PG 回捞 + CPU）——再往上需加 Qdrant/PG 资源或 api pod。
- **冷/唯一查询**：无缓存收益，vector 仍 ~34 QPS（真调 embedding）、full 仍 ~7 QPS（真调 LLM）。真实流量介于两者之间，**收益随查询重复率上升**。
- keyword 始终 ES-bound（~164 QPS），缓存帮不上。

## 7. 异常 / 边界 / 安全（V1 已修项复核）
- topK 边界 → 友好中文「topK 需在 1~50 之间」✅（V1 英文报错已修）
- 纯图片 PDF → 回退图片管道 COMPLETED ✅（V1 FAILED 已修）
- zip 直传 → 明确指引「压缩包请通过前端上传」✅
- 越权 kb / SQL 注入 / emoji / 超长 query → 安全稳健 ✅

## 8. 复现
- 判别性语料 + 改写查询：`gen_discriminative.py` + 改写 query 集
- 热点压测（体现缓存）：`hot_loadtest.py <token> <kb> <strategy> <ladder>`（固定查询池重复打）
- 唯一查询压测（测原始 embedding 吞吐，缓存不命中）：`rag_loadtest.py`（query 带 #i 唯一后缀）
- 数据保留：KB 638 / 643 / 644（测后未删）

## 9. 后续可选优化
- vector/hybrid 要再上量：加 Qdrant/PG 资源或水平扩 api pod（当前瓶颈是真实吞吐非配置）。
- 冷查询 embedding：DashScope 侧无法绕开，唯有缓存热点；可考虑常见查询预热。
- 语义缓存升级：当前为精确 query 匹配；如需"近义查询也命中"，可加查询向量相似度阈值匹配（复杂度更高，需权衡误命中）。
