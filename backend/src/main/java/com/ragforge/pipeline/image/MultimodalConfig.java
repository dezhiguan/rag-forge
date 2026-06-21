package com.ragforge.pipeline.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MultimodalProperties.class)
public class MultimodalConfig {

  @Bean
  public OcrClient ocrClient(MultimodalProperties properties, ObjectMapper objectMapper) {
    return new RemoteOcrClient(properties, objectMapper);
  }

  @Bean
  public VisionCaptionClient visionCaptionClient(
      MultimodalProperties properties, ObjectMapper objectMapper) {
    return new RemoteVisionCaptionClient(properties, objectMapper);
  }

  @Bean
  public ImageEmbeddingClient imageEmbeddingClient(
      MultimodalProperties properties, ObjectMapper objectMapper) {
    return new RemoteImageEmbeddingClient(properties, objectMapper);
  }
}
