package com.ragforge.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class AsyncConfigTest {

  private final AsyncConfig config = new AsyncConfig();

  @Test
  void evalExperimentExecutor_usesAtLeastOneThreadAndEvalPrefix() {
    EvalProperties properties = new EvalProperties();
    properties.setMaxConcurrentQuestions(0);

    ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.evalExperimentExecutor(properties);
    try {
      assertThat(executor.getCorePoolSize()).isEqualTo(1);
      assertThat(executor.getMaxPoolSize()).isEqualTo(1);
      assertThat(executor.getThreadNamePrefix()).isEqualTo("eval-exp-");
    } finally {
      executor.shutdown();
    }
  }

  @Test
  void retrievalExecutor_clampsPoolAndQueueSettings() {
    RetrievalProperties properties = new RetrievalProperties();
    properties.setExecutorCoreSize(0);
    properties.setExecutorMaxSize(0);
    properties.setExecutorQueueCapacity(-5);

    ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.retrievalExecutor(properties);
    try {
      assertThat(executor.getCorePoolSize()).isEqualTo(1);
      assertThat(executor.getMaxPoolSize()).isEqualTo(1);
      assertThat(executor.getThreadNamePrefix()).isEqualTo("retrieval-");
      assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
    } finally {
      executor.shutdown();
    }
  }

  @Test
  void documentProcessExecutor_usesCallerRunsPolicyAndExpectedSizing() {
    ThreadPoolTaskExecutor executor = config.documentProcessExecutor();
    try {
      assertThat(executor.getCorePoolSize()).isEqualTo(2);
      assertThat(executor.getMaxPoolSize()).isEqualTo(4);
      assertThat(executor.getThreadNamePrefix()).isEqualTo("document-process-");
      assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
          .isInstanceOf(java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy.class);
    } finally {
      executor.shutdown();
    }
  }

  @Test
  void goldenReplayExecutor_usesSingleThread() {
    ThreadPoolTaskExecutor executor = config.goldenReplayExecutor();
    try {
      assertThat(executor.getCorePoolSize()).isEqualTo(1);
      assertThat(executor.getMaxPoolSize()).isEqualTo(1);
      assertThat(executor.getThreadNamePrefix()).isEqualTo("golden-replay-");
    } finally {
      executor.shutdown();
    }
  }
}
