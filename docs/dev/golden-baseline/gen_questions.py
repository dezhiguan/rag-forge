# -*- coding: utf-8 -*-
"""编写 100 条平台级黄金题（事实取自 corpus，格式×难度双标签）。
每题：question / expectedTextSnippets / judgeTags(格式+难度) / judgeEnabled=True / isCore=True。
否定/无答案题 expectedTextSnippets 留空（库中确无该事实）。"""
import json, os
Q = []
def add(q, snips, fmt, tier):
    Q.append({"question": q, "expectedTextSnippets": snips,
              "judgeTags": [fmt, tier], "judgeEnabled": True, "isCore": True})

# ============ 精确 / 关键词（22） ============
T="exact"
add("RAGForge 后端使用的 Spring Boot 版本号是多少？", ["Java 21 与 Spring Boot 3.5.15"], "md", T)
add("RAGForge 文档向量的维度是多少维？", ["文档向量统一为 2560 维"], "md", T)
add("pgvector 0.8 的向量索引维度上限是多少？", ["超过了 pgvector 0.8 的索引维度上限 2000"], "md", T)
add("full 检索策略的默认并发是多少？", ["full：改写 + 混合 + 重排", "默认并发为 1"], "md", T)
add("应用层对外暴露的 NodePort 端口号是多少？", ["应用层 NodePort 31090"], "pdf", T)
add("api 服务部署了几个副本？", ["api：3 个副本"], "pdf", T)
add("judge 服务部署了几个副本？", ["judge：1 个副本"], "pdf", T)
add("文件存储挂载的本地路径是什么？", ["hostPath，挂载路径为 /data/files"], "pdf", T)
add("RAGForge 应用层部署在什么集群？", ["k3s 单节点集群，命名空间为 ragforge"], "pdf", T)
add("JWT 的签名算法是什么？", ["签名算法为 RS256"], "docx", T)
add("access token 的有效期是多久？", ["access token 有效期为 15 分钟"], "docx", T)
add("开启记住我后 refresh token 的有效期是多久？", ["记住我后为 30 天滑动续期"], "docx", T)
add("EMBEDDING 用途使用的是哪个模型？", ["EMBEDDING", "qwen3-vl-embedding"], "html", T)
add("RERANK 用途使用的是哪个模型？", ["RERANK", "qwen3-rerank"], "html", T)
add("评测判分 JUDGE 用的是哪个模型、哪个供应商？", ["JUDGE", "deepseek-v4-flash", "DeepSeek"], "html", T)
add("关键词检索依赖的 Elasticsearch 版本是多少？", ["Elasticsearch 8.15 的 BM25"], "txt", T)
add("文档处理管道使用的消息队列主题（topic）是什么？", ["主题为 ragforge-document-process"], "txt", T)
add("vector 策略的默认并发是多少？", ["vector", "48"], "csv", T)
add("keyword 策略的默认并发是多少？", ["keyword", "32"], "csv", T)
add("架构分层图中数据层包含哪两个组件？", ["数据层：PostgreSQL + Elasticsearch"], "png", T)
add("frontend 前端部署了几个副本？", ["frontend 前端：2 个副本"], "jpg", T)
add("关键指标卡中 access token 的有效期是多少？", ["access token 有效期：15 分钟"], "webp", T)

# ============ 语义 / 改写（22） ============
T="semantic"
add("这个平台是用什么编程语言和框架写后端的？", ["Java 21 与 Spring Boot 3.5.15"], "md", T)
add("RAGForge 大概是做什么的、服务于谁？", ["定位为基础设施层，为 CareerMate", "知识检索、RAG 应答与 MCP API"], "md", T)
add("为什么现在的向量查询这么慢、没走索引？", ["超过了 pgvector", "没有 HNSW 索引，走的是顺序扫描"], "md", T)
add("哪一种检索方式会用到重排模型？", ["full", "唯一调用 Rerank 的策略"], "md", T)
add("前端技术选型是怎样的？", ["前端采用 Vue 3 与 Vite，使用纯 JavaScript"], "md", T)
add("一个用户请求从外到内大概怎么走到应用的？", ["域名 → 入口层 Nginx → 应用层 NodePort 31090"], "pdf", T)
add("同一个后端镜像是怎么区分不同角色的？", ["按环境变量 RAGFORGE_ROLE 启动为不同角色"], "pdf", T)
add("负责消费消息、处理文档的是哪个角色？", ["worker：1 个副本，消费 RocketMQ 处理文档"], "pdf", T)
add("后端是怎么校验登录令牌的？", ["自研的 JwtVerifier，通过 JWKS 验签"], "docx", T)
add("这个系统里有哪些用户角色？", ["ADMIN、KB_EDITOR、KB_VIEWER 与 SERVICE_ACCOUNT"], "docx", T)
add("知识库的访问权限是怎么统一管控的？", ["统一经过 KbAccessGuard 校验"], "docx", T)
add("组织是怎么设计的，和早期有什么不同？", ["GitHub 式的个人加组织结构，已移除早期的 tenant"], "docx", T)
add("查询改写用的是哪个模型？", ["REWRITE", "qwen-turbo"], "html", T)
add("生成最终回答用的是哪个模型？", ["ANSWER", "qwen-plus"], "html", T)
add("判分的花费算到哪个组织头上？", ["评测判分（JUDGE）的成本归属到系统组织"], "html", T)
add("MCP 能力是基于什么技术做的？", ["基于 Spring AI 的 MCP WebMVC SSE"], "txt", T)
add("中文分词坏了或者没装会怎样？", ["IK 插件缺失时，回退到 standard 分词器"], "txt", T)
add("认证撤销和限流这些是靠什么实现的？", ["依赖 Redis", "认证撤销、API Key 限流与 ShedLock"], "txt", T)
add("哪个检索策略是把向量和关键词结果融合起来的？", ["hybrid", "RRF 融合"], "csv", T)
add("架构里消息队列和缓存分别是什么？", ["消息与缓存：RocketMQ + Redis"], "png", T)
add("检索策略图里默认用的是哪种策略？", ["vector：默认，向量相似度"], "gif", T)
add("指标卡里提到记住我能维持多久？", ["记住我时长：30 天滑动"], "webp", T)

# ============ 多跳 / 聚合（16） ============
T="multihop"
add("full 策略既是唯一调用重排的，它的默认并发又是多少？", ["唯一调用 Rerank 的策略", "full", "1"], "csv", T)
add("向量维度是多少、为什么导致没有 HNSW 索引？", ["2560 维", "超过了 pgvector", "顺序扫描"], "md", T)
add("api、worker、judge、frontend 四个角色各有几个副本，合计多少个？", ["api：3", "worker：1", "judge：1", "frontend：2"], "pdf", T)
add("EMBEDDING 模型的维度和文档向量维度是否一致，是多少？", ["qwen3-vl-embedding", "2560 维"], "html", T)
add("评测判分用哪个模型、成本归到哪个组织？", ["deepseek-v4-flash", "成本归属到系统组织"], "html", T)
add("五种检索策略里，哪些用到向量、哪个不用？", ["vector", "hybrid", "rewrite", "full", "keyword", "BM25"], "md", T)
add("keyword 策略依赖哪个组件、用什么算法、分词插件缺失怎么办？", ["Elasticsearch", "BM25", "回退到 standard"], "txt", T)
add("请求入口经过 Nginx 后到应用层的哪个端口、部署在什么集群？", ["入口层 Nginx", "NodePort 31090", "k3s"], "pdf", T)
add("access token 和 refresh token 的有效期分别是多少？", ["access token 有效期为 15 分钟", "refresh token", "7 天"], "docx", T)
add("REWRITE 和 ANSWER 两个用途分别用哪个模型？", ["REWRITE", "qwen-turbo", "ANSWER", "qwen-plus"], "html", T)
add("文档处理链路用哪个消息队列、主题和消费组分别叫什么？", ["RocketMQ", "ragforge-document-process", "ragforge-doc-process-group"], "txt", T)
add("hybrid 与 rewrite 两个策略的默认并发分别是多少？", ["hybrid", "20", "rewrite", "16"], "csv", T)
add("系统组织的 org_id 是多少、绑定给谁？", ["系统组织的 org_id 为 0，绑定平台超级管理员"], "docx", T)
add("架构分层从入口到数据层依次是哪几层？", ["入口层 Nginx", "应用层：k3s", "数据层：PostgreSQL + Elasticsearch"], "png", T)
add("部署角色卡里 api 和 frontend 的副本数分别是多少？", ["api 服务：3 个副本", "frontend 前端：2 个副本"], "jpg", T)
add("full 策略在检索策略图里的完整描述是什么？", ["full：改写+混合+重排，调用 Rerank"], "gif", T)

# ============ 否定 / 无答案（20）——库中确无，期望不臆造 ============
T="noanswer"
for q in [
    "RAGForge 的文档处理消息队列用的是 Kafka 吗？消费组怎么配置？",
    "平台的向量数据库用的是 Milvus 吗？",
    "RAGForge 的关系型数据库是 MySQL 吗？版本是多少？",
    "RAGForge 提供 iOS 和 Android 移动客户端吗？如何下载安装？",
    "平台登录支持短信二次验证（2FA）吗？如何开启？",
    "RAGForge 对外提供 GraphQL 接口吗？端点在哪里？",
    "系统的每月订阅价格是多少人民币？",
    "平台的灰度发布用的是 Istio 服务网格吗？",
    "RAGForge 后端是用 Python FastAPI 写的吗？",
    "前端框架用的是 React 还是 Angular？",
    "平台的对象存储默认用的是 AWS S3 吗？桶名是什么？",
    "RAGForge 的 CEO 是谁？公司注册在哪个城市？",
    "平台支持视频文件的检索吗？支持的最长时长是多少？",
    "RAGForge 的 SLA 承诺可用性是几个 9？",
    "检索服务用的重排模型是本地部署的 bge-reranker 吗？",
    "平台的用户总数和日活是多少？",
    "RAGForge 的缓存用的是 Memcached 吗？",
    "系统的定时任务调度用的是 Quartz 集群吗？",
    "平台前端的单元测试框架用的是 Jest 吗？覆盖率要求多少？",
    "RAGForge 支持私有化 Windows Server 部署吗？",
]:
    add(q, [], "mixed", T)

# ============ 长尾 / 边界（20） ============
T="longtail"
add("网关在 refresh token 旋转时提供多长的宽限期？", ["网关提供 60 秒的旋转宽限期"], "docx", T)
add("后端 JWT 验签是用 nimbus 库吗？具体用什么？", ["自研的 JwtVerifier，通过 JWKS 验签，而非 nimbus 库"], "docx", T)
add("hybrid 策略融合向量与关键词用的是什么算法？", ["RRF 融合"], "gif", T)
add("Redis 除了认证撤销和限流，还承担什么分布式能力？", ["ShedLock 分布式锁"], "txt", T)
add("文档处理的 RocketMQ 消费组名称具体是什么？", ["ragforge-doc-process-group"], "txt", T)
add("OCR 用途使用的是哪个模型？", ["OCR", "qwen-vl-ocr"], "html", T)
add("对象存储抽象就绪了吗？默认用的是什么？", ["对象存储抽象已就绪，但默认使用本地盘"], "pdf", T)
add("SERVICE_ACCOUNT 是平台的一种角色吗？", ["SERVICE_ACCOUNT"], "docx", T)
add("keyword 策略在指标表里的向量维度填的是什么？", ["keyword", "-"], "csv", T)
add("rewrite 策略的完整说明是什么？", ["改写查询后多路向量"], "csv", T)
add("平台的线上域名是什么？", ["线上域名为 ragforge.net"], "md", T)
add("关键指标卡里写的当前索引状态是什么？", ["当前索引：无 HNSW，顺序扫描"], "webp", T)
add("检索策略图里 keyword 对应的算法标注是什么？", ["keyword：BM25 关键词"], "gif", T)
add("数据层独立部署包含哪四个中间件？", ["PostgreSQL（含 pgvector）、Elasticsearch、RocketMQ 与 Redis"], "pdf", T)
add("组织模型移除了早期的什么概念？", ["已移除早期的 tenant 概念"], "docx", T)
add("ANSWER 用途除了 RAG 应答还用于什么？", ["ANSWER", "RAG 应答与调试台"], "html", T)
add("架构分层图里向量维度和扫描方式怎么写的？", ["向量维度：2560 维，顺序扫描"], "png", T)
add("部署角色卡里入口端口标注的是什么？", ["入口端口：NodePort 31090"], "jpg", T)
add("full 策略在指标表里是否调用重排、并发多少？", ["full", "是", "1"], "csv", T)
add("keyword 检索缺失 IK 插件时回退到哪个分词器？", ["回退到 standard 分词器"], "txt", T)

# ---- 校验并输出 ----
from collections import Counter
tiers = Counter(q["judgeTags"][1] for q in Q)
fmts = Counter(q["judgeTags"][0] for q in Q)
print("总题数:", len(Q))
print("难度分布:", dict(tiers))
print("格式分布:", dict(fmts))
assert len(Q) == 100, "题数必须为 100"
out = os.path.join(os.path.dirname(__file__), "golden_questions.json")
with open(out, "w", encoding="utf-8") as f:
    json.dump(Q, f, ensure_ascii=False, indent=2)
print("已写出", out)
