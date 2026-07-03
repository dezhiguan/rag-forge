package com.ragforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ragforge.retrieval")
public class RetrievalProperties {

  private Strategy keyword = new Strategy(20, 5000);
  private Strategy vector = new Strategy(5, 8000);
  // hybrid 默认并发由 5 提到 20：单次混合检索并不重，5 太保守导致早限流；配合各阶段超时兜底放大 QPS。
  private Strategy hybrid = new Strategy(20, 12000);
  private Strategy full = new Strategy(1, 15000);
  private Strategy rewrite = new Strategy(3, 10000);

  private int executorCoreSize = 4;
  private int executorMaxSize = 12;
  private int executorQueueCapacity = 100;
  private int stageTimeoutMs = 8000;

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
