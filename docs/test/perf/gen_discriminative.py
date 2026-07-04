#!/usr/bin/env python3
"""判别性语料:同簇内多个语义相近文档,query 需精确区分目标 doc,考验各策略排序质量。"""
import os, json
OUT="dcorpus"; os.makedirs(OUT,exist_ok=True)
docs=[]  # (name, content, query) —— query 精确指向该 doc,同簇其他 doc 是干扰项
def d(name, content, q): docs.append({"file":name+".txt","content":content,"query":q})

# 簇1: Redis 的多个方面(易混淆)
d("redis_persistence","Redis 持久化有 RDB 快照和 AOF 日志两种方式,RDB 定期全量保存二进制快照,AOF 追加记录每条写命令,生产环境常混合使用兼顾恢复速度和数据安全。","Redis 的 RDB 和 AOF 持久化区别")
d("redis_cluster","Redis Cluster 采用 16384 个哈希槽分片,每个主节点负责一部分槽位,客户端根据 key 的 CRC16 定位节点,支持在线扩缩容和主从故障转移。","Redis Cluster 用哈希槽怎么分片")
d("redis_expire","Redis 过期键采用惰性删除加定期删除策略,访问时检查过期则删除,同时后台定时随机抽样清理,配合 maxmemory 淘汰策略如 LRU/LFU。","Redis 过期键的删除策略")
d("redis_pipeline","Redis Pipeline 把多条命令一次性发送减少网络往返 RTT,服务端顺序执行后批量返回结果,显著提升批量操作吞吐,但不保证原子性。","Redis Pipeline 怎么减少网络开销")
# 簇2: 消息队列对比
d("kafka_partition","Kafka 通过分区实现并行,每个分区是有序的追加日志,消费者组内每个分区只被一个消费者消费,分区数决定最大并行度。","Kafka 分区如何决定消费并行度")
d("kafka_isr","Kafka 用 ISR 同步副本集合保证可靠,只有 ISR 中的副本才能被选为 leader,acks=all 时需所有 ISR 确认才算写入成功。","Kafka 的 ISR 副本机制")
d("rabbitmq_exchange","RabbitMQ 通过 Exchange 路由消息,direct 按 routing key 精确匹配,topic 按通配符,fanout 广播到所有绑定队列。","RabbitMQ Exchange 有哪几种路由类型")
d("rocketmq_order","RocketMQ 顺序消息把同一业务的消息发到同一队列,消费端单线程顺序拉取,保证局部有序,适合订单状态流转场景。","RocketMQ 怎么保证消息顺序")
# 簇3: 一致性/分布式
d("raft_election","Raft 的 leader 选举:follower 超时未收到心跳转为 candidate 发起投票,获得多数派选票成为 leader,任期 term 单调递增避免脑裂。","Raft 的 leader 选举过程")
d("raft_logrep","Raft 日志复制:leader 接收写请求追加日志并广播给 follower,多数派持久化后提交并应用到状态机,保证已提交日志不丢。","Raft 日志复制怎么保证已提交不丢")
d("paxos","Basic Paxos 分为 prepare 和 accept 两阶段,proposer 用递增编号提案,多数派 acceptor 承诺后才能提交,保证唯一值达成共识。","Paxos 的两阶段提交过程")
d("2pc","两阶段提交 2PC 有协调者和参与者,准备阶段各方投票,提交阶段协调者根据投票决定全局提交或回滚,阻塞且协调者单点风险。","两阶段提交 2PC 的协调者作用")
# 簇4: 数据库索引
d("mysql_btree","MySQL InnoDB 用 B+ 树索引,非叶子节点只存键,叶子节点存数据并用链表相连,聚簇索引叶子存整行,范围查询高效。","MySQL InnoDB 的 B+ 树索引结构")
d("mysql_mvcc","MySQL InnoDB 的 MVCC 通过 undo log 和 read view 实现,每行有隐藏事务 id,读时按可见性规则找到合适版本,实现读不加锁。","MySQL MVCC 用 undo log 怎么实现")
d("pg_gin","PostgreSQL GIN 倒排索引适合数组/JSONB/全文检索,把每个元素映射到包含它的行列表,支持包含查询,写入较慢但查询快。","PostgreSQL GIN 索引适合什么场景")
d("qdrant_hnsw","Qdrant 用 HNSW 分层图索引做向量近似最近邻,构建多层跳表式图,查询从顶层贪心下降,复杂度接近对数级,支持过滤检索。","Qdrant HNSW 向量索引怎么查询")
# 簇5: 缓存/一致性
d("cache_aside","Cache Aside 模式:读时先查缓存未命中再查库回填,写时先更新库再删除缓存,可能短暂不一致但实现简单最常用。","Cache Aside 读写缓存怎么处理")
d("cache_penetration","缓存穿透指查询不存在的数据每次都打到库,解决办法是缓存空值或用布隆过滤器提前拦截不存在的 key。","缓存穿透用布隆过滤器怎么解决")
d("cache_avalanche","缓存雪崩指大量 key 同一时刻过期导致请求全部打到库,解决办法是给过期时间加随机抖动,并做多级缓存和限流降级。","缓存雪崩过期时间加随机抖动")
d("cache_breakdown","缓存击穿指某个热点 key 过期瞬间大量并发打到库,解决办法是互斥锁只让一个线程重建缓存,或热点数据永不过期。","缓存击穿热点 key 用互斥锁重建")
json.dump(docs,open(f"{OUT}/dcorpus.json","w"),ensure_ascii=False,indent=1)
for x in docs: open(f"{OUT}/{x['file']}","w").write(x["content"])
print(f"生成 {len(docs)} 个判别性文档(5簇,同簇内语义相近)")
