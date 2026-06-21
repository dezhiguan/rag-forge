package com.ragforge.pipeline.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.config.DashScopeProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({MultimodalProperties.class, DashScopeProperties.class})
public class MultimodalConfig {

  @Bean
  public OcrClient ocrClient(
      MultimodalProperties properties, DashScopeProperties dashScopeProperties, ObjectMapper objectMapper) {
    return new RemoteOcrClient(properties, dashScopeProperties, objectMapper);
  }

  @Bean
  public VisionCaptionClient visionCaptionClient(
      MultimodalProperties properties, DashScopeProperties dashScopeProperties, ObjectMapper objectMapper) {
    return new RemoteVisionCaptionClient(properties, dashScopeProperties, objectMapper);
  }

  @Bean
  public ImageEmbeddingClient imageEmbeddingClient(
      MultimodalProperties properties, DashScopeProperties dashScopeProperties, ObjectMapper objectMapper) {
    return new RemoteImageEmbeddingClient(properties, dashScopeProperties, objectMapper);
  }
}
