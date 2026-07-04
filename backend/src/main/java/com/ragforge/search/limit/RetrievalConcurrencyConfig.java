package com.ragforge.search.limit;

import com.ragforge.config.RetrievalProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 按 {@code ragforge.retrieval.distributed-limit.enabled} 选择检索并发限流实现，支持不重发版回退。 */
@Slf4j
@Configuration
public class RetrievalConcurrencyConfig {

  @Bean
  public ConcurrencyLimiter retrievalConcurrencyLimiter(
      RetrievalProperties properties,
      StringRedisTemplate redisTemplate,
      MeterRegistry meterRegistry) {
    if (properties.getDistributedLimit().isEnabled()) {
      log.info("检索并发限流：分布式(Redis ZSET)模式");
      return new RedisConcurrencyLimiter(redisTemplate, meterRegistry);
    }
    log.info("检索并发限流：本地(进程内 Semaphore)模式");
    return new LocalConcurrencyLimiter();
  }
}
