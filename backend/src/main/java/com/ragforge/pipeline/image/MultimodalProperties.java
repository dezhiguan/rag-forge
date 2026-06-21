package com.ragforge.pipeline.image;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ragforge.multimodal")
public class MultimodalProperties {

  private boolean enabled = true;
  private int timeoutMs = 30000;
  private int maxConcurrent = 1;
  private String ocrEndpoint = "";
  private String captionEndpoint = "";
  private String imageEmbeddingEndpoint = "";
  private String apiKey = "";
  private double textWeight = 0.7;
  private double imageWeight = 0.3;
}
