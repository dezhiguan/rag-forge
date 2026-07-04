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
    /** 输出向量维度（qwen3-vl-embedding 支持 Matryoshka 256~2560，默认 1024 以匹配 Qdrant collection）。 */
    private int dimension = 1024;
  }

  @Data
  public static class Ocr {
    private String model = "qwen-vl-ocr";
    private String endpoint =
        "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private int timeoutMs = 30000;
  }
}
