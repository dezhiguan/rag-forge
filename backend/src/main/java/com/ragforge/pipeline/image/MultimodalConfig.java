package com.ragforge.pipeline.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.config.EmbeddingProperties;
import com.ragforge.metrics.RagforgeMetrics;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({MultimodalProperties.class, EmbeddingProperties.class})
public class MultimodalConfig {

  @Bean
  public OcrClient ocrClient(
      EmbeddingProperties properties,
      ObjectMapper objectMapper,
      RagforgeMetrics metrics,
      com.ragforge.modelcenter.ModelUsageRecorder modelUsageRecorder) {
    return new RemoteOcrClient(properties, objectMapper, metrics, modelUsageRecorder);
  }
}
