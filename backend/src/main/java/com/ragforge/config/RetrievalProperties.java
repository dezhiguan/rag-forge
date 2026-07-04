package com.ragforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ragforge.retrieval")
public class RetrievalProperties {

  private Strategy keyword = new Strategy(40, 5000);
  // vector 16→48：query 向量缓存命中后单请求 ~15ms（embedding 归零），限流器成新瓶颈，放大吃满 Qdrant 余量。
  private Strategy vector = new Strategy(48, 5000);
  // hybrid 20→32：缓存降低 vector 臂延迟后放大；配合执行器扩容缓解 504 尾。
  private Strategy hybrid = new Strategy(32, 5000);
  // full 1→4 / rewrite 3→8：受 DashScope LLM/rerank 配额约束（非硬件），放大后需盯 DashScope 429。
  private Strategy full = new Strategy(4, 15000);
  private Strategy rewrite = new Strategy(8, 10000);

  private int executorCoreSize = 12;
  private int executorMaxSize = 64;
  private int executorQueueCapacity = 200;
  private int stageTimeoutMs = 6000;

  /** 分布式并发限流（Redis）配置。 */
  private DistributedLimit distributedLimit = new DistributedLimit();

  @Data
  public static class Strategy {
    private int maxConcurrent;
    private int timeoutMs;

    public Strategy() {}

    public Strategy(int maxConcurrent, int timeoutMs) {
      this.maxConcurrent = maxConcurrent;
      this.timeoutMs = timeoutMs;
    }
  }

  @Data
  public static class DistributedLimit {
    /** true=Redis 全局并发限流；false=回退进程内 Semaphore（每 pod 本地）。 */
    private boolean enabled = true;

    /** 许可租约毫秒，用于回收持有者崩溃后泄漏的许可；实际取本值与(策略超时+5s)的较大者。 */
    private long leaseMs = 20000;
  }
}
