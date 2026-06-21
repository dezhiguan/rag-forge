package com.ragforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "embedding")
public class EmbeddingProperties {

  private String apiKey = "";

  private Vl vl = new Vl();

  private Ocr ocr = new Ocr();

  @Data
  public static class Vl {
    private String model = "qwen3-vl-embedding";
    private String endpoint =
        "https://dashscope.aliyuncs.com/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding";
    private int timeoutMs = 15000;
    private int batchSize = 10;
  }

  @Data
  public static class Ocr {
    private String model = "qwen-vl-ocr";
    private String endpoint =
        "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private int timeoutMs = 30000;
  }
}
