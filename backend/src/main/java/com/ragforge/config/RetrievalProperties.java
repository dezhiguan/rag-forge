package com.ragforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ragforge.retrieval")
public class RetrievalProperties {

  private Strategy keyword = new Strategy(40, 5000);
  // vector 5→16：Qdrant 检索仅 ~5ms、硬件余量大，5 太保守；提升 QPS（受 DashScope embedding 配额约束）。
  private Strategy vector = new Strategy(16, 5000);
  // hybrid 并发 20；超时 12s→5s，越限快速失败不拖成 504。
  private Strategy hybrid = new Strategy(20, 5000);
  // full 1→4 / rewrite 3→8：受 DashScope LLM/rerank 配额约束（非硬件），放大后需盯 DashScope 429。
  private Strategy full = new Strategy(4, 15000);
  private Strategy rewrite = new Strategy(8, 10000);

  private int executorCoreSize = 8;
  private int executorMaxSize = 48;
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
