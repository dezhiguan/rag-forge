package com.ragforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ragforge.eval")
public class EvalProperties {

  private int maxConcurrentQuestions = 2;
  private long questionTimeoutMs = 30000;
}
