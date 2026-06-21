package com.ragforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ragforge.retrieval")
public class RetrievalProperties {

  private Strategy keyword = new Strategy(20, 5000);
  private Strategy vector = new Strategy(5, 8000);
  private Strategy hybrid = new Strategy(5, 12000);
  private Strategy full = new Strategy(1, 15000);
  private Strategy rewrite = new Strategy(3, 10000);

  private int executorCoreSize = 4;
  private int executorMaxSize = 12;
  private int executorQueueCapacity = 100;
  private int stageTimeoutMs = 8000;

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
}
