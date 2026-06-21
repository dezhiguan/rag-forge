package com.ragforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.dashscope")
public class DashScopeProperties {

  public static final String EMBEDDING_URL =
      "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";

  private String apiKey = "your-key-here";

  private String model = "text-embedding-v4";

  private String visionModel = "qwen-vl-max";

  private String imageEmbeddingModel = "multimodal-embedding-v1";

  private String visionEndpoint = "";

  private String imageEmbeddingEndpoint = "";

  /** DashScope text-embedding-v4 allows at most 10 texts per request. */
  private int batchSize = 10;

  private int timeout = 30000;

  private int dimension = 1024;

  /** Minimum interval between two embedding API calls. */
  private int minRequestIntervalMs = 0;

  /** Maximum concurrent embedding API calls allowed inside the JVM. */
  private int maxConcurrentRequests = 1;
}
