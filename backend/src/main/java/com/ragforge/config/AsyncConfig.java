package com.ragforge.config;

import com.ragforge.common.MdcContext;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
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

  /**
   * 检索日志写入专用池（M3）。此前 RetrievalLogService 的裸 @Async 因无 @Primary/无 taskExecutor 而回退到
   * SimpleAsyncTaskExecutor —— 每次检索都新建线程、无界，高 QPS 下线程暴涨。这里绑定有界池；溢出退化为
   * 调用方同步写（CallerRunsPolicy），既不丢日志也不爆线程（日志是旁路，极端负载下宁可慢一点）。
   */
  @Bean
  public Executor retrievalLogExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(1000);
    executor.setThreadNamePrefix("retrieval-log-");
    executor.setTaskDecorator(MdcContext.taskDecorator());
    executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
    executor.initialize();
    return executor;
  }

  /**
   * RAG 应答流式专用池（M4）。此前 AnswerService 的 CompletableFuture.runAsync 无 executor，落到全 JVM 共享的
   * ForkJoinPool.commonPool；而应答是 SSE 长任务（可达 600s），会占满公共池拖累并行流等其它使用者。这里绑定
   * 有界池、不排队（长流任务无限积压无意义）、满即拒绝，由调用方转成友好「服务繁忙」提示。
   */
  @Bean
  public Executor answerExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(32);
    executor.setQueueCapacity(0);
    executor.setThreadNamePrefix("answer-stream-");
    executor.setTaskDecorator(MdcContext.taskDecorator());
    executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    executor.initialize();
    return executor;
  }

  /**
   * 文档处理心跳调度器（M1）：processDocument 期间周期性刷新 updated_at，避免长阶段(embedding >5min)被
   * markProcessingIfRunnable 的 5min 陈旧窗口误判卡死而并发重跑。守护线程 + 池大小 2(处理并发上限 4，心跳任务极短)。
   */
  @Bean(destroyMethod = "shutdown")
  public ScheduledExecutorService documentHeartbeatScheduler() {
    AtomicInteger idx = new AtomicInteger();
    return Executors.newScheduledThreadPool(
        2,
        r -> {
          Thread t = new Thread(r, "doc-heartbeat-" + idx.incrementAndGet());
          t.setDaemon(true);
          return t;
        });
  }
}
