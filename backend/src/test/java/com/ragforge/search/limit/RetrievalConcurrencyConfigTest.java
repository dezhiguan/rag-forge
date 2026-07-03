package com.ragforge.search.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ragforge.config.RetrievalProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class RetrievalConcurrencyConfigTest {

  private final RetrievalConcurrencyConfig config = new RetrievalConcurrencyConfig();
  private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @Test
  void enabledGivesRedisLimiter() {
    RetrievalProperties props = new RetrievalProperties();
    props.getDistributedLimit().setEnabled(true);

    ConcurrencyLimiter limiter =
        config.retrievalConcurrencyLimiter(props, redisTemplate, meterRegistry);

    assertThat(limiter).isInstanceOf(RedisConcurrencyLimiter.class);
  }

  @Test
  void disabledGivesLocalLimiter() {
    RetrievalProperties props = new RetrievalProperties();
    props.getDistributedLimit().setEnabled(false);

    ConcurrencyLimiter limiter =
        config.retrievalConcurrencyLimiter(props, redisTemplate, meterRegistry);

    assertThat(limiter).isInstanceOf(LocalConcurrencyLimiter.class);
  }
}
