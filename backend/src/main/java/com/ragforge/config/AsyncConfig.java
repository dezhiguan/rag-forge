package com.ragforge.config;

import com.ragforge.common.MdcContext;
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
    executor.setTaskDecorator(MdcContext.taskDecorator());
    executor.initialize();
    return executor;
  }

  @Bean
  public Executor retrievalExecutor(RetrievalProperties retrievalProperties) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(Math.max(1, retrievalProperties.getExecutorCoreSize()));
    executor.setMaxPoolSize(Math.max(1, retrievalProperties.getExecutorMaxSize()));
    executor.setQueueCapacity(Math.max(0, retrievalProperties.getExecutorQueueCapacity()));
    executor.setThreadNamePrefix("retrieval-");
    executor.setTaskDecorator(MdcContext.taskDecorator());
    executor.initialize();
    return executor;
  }

  @Bean
  public ThreadPoolTaskExecutor documentProcessExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("document-process-");
    executor.setTaskDecorator(MdcContext.taskDecorator());
    executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
    executor.initialize();
    return executor;
  }

  @Bean
  public ThreadPoolTaskExecutor goldenReplayExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(20);
    executor.setThreadNamePrefix("golden-replay-");
    executor.setTaskDecorator(MdcContext.taskDecorator());
    executor.initialize();
    return executor;
  }
}
