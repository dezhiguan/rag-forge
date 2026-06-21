package com.ragforge.pipeline.image;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ragforge.multimodal")
public class MultimodalProperties {

  private boolean enabled = true;
  private int timeoutMs = 30000;
  private int maxConcurrent = 1;
  private Embedded embedded = new Embedded();

  @Data
  public static class Embedded {
    private int minImageBytes = 8 * 1024;
    private int maxConcurrentImageTasks = 3;
    private long maxProcessingMsPerDoc = 120000L;
  }
}
