package com.ragforge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.service.LlmService;
import com.ragforge.service.impl.LlmServiceImpl;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class LlmServiceConfiguration {

  @Bean
  @ConditionalOnMissingBean(LlmService.class)
  public LlmService llmService(
      @Qualifier("llmRestTemplate") RestTemplate llmRestTemplate, ObjectMapper objectMapper) {
    return new LlmServiceImpl(llmRestTemplate, objectMapper);
  }
}
