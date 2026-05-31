package com.ragforge.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

  @Bean
  public Executor evalExperimentExecutor(EvalProperties evalProperties) {
    int poolSize = Math.max(1, evalProperties.getMaxConcurrentQuestions());
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(poolSize);
    executor.setMaxPoolSize(poolSize);
    executor.setQueueCapacity(200);
    executor.setThreadNamePrefix("eval-exp-");
    executor.initialize();
    return executor;
  }
}
