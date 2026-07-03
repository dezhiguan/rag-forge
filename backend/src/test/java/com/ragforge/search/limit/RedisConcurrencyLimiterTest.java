package com.ragforge.search.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.search.limit.ConcurrencyLimiter.Guard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class RedisConcurrencyLimiterTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ZSetOperations<String, String> zSetOperations;

  private SimpleMeterRegistry meterRegistry;
  private RedisConcurrencyLimiter limiter;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    limiter = new RedisConcurrencyLimiter(redisTemplate, meterRegistry);
  }

  private void stubScript(Long result) {
    when(redisTemplate.execute(
            ArgumentMatchers.<RedisScript<Long>>any(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(result);
  }

  @Test
  void acquireSucceedsWhenScriptReturnsOne() {
    stubScript(1L);

    Guard guard = limiter.tryAcquire("hybrid", 20, 20000);

    assertThat(guard).isNotNull();
  }

  @Test
  void acquireRejectedWhenScriptReturnsZero() {
    stubScript(0L);

    Guard guard = limiter.tryAcquire("hybrid", 20, 20000);

    assertThat(guard).isNull();
    assertThat(meterRegistry.find(RedisConcurrencyLimiter.FAIL_OPEN_METRIC).counter()).isNull();
  }

  @Test
  void acquireRejectedWhenScriptReturnsNull() {
    stubScript(null);

    assertThat(limiter.tryAcquire("hybrid", 20, 20000)).isNull();
  }

  @Test
  void failsOpenAndCountsWhenRedisThrows() {
    when(redisTemplate.execute(
            ArgumentMatchers.<RedisScript<Long>>any(),
            anyList(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenThrow(new RuntimeException("redis down"));

    Guard guard = limiter.tryAcquire("hybrid", 20, 20000);

    // fail-open：放行(非 null)，且计数告警
    assertThat(guard).isNotNull();
    assertThat(meterRegistry.counter(RedisConcurrencyLimiter.FAIL_OPEN_METRIC, "reason", "redis").count())
        .isEqualTo(1.0);
    // fail-open 守卫的 close 无副作用，不应抛异常
    guard.close();
  }

  @Test
  void releaseRemovesMemberFromZSet() {
    stubScript(1L);
    when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

    Guard guard = limiter.tryAcquire("full", 1, 20000);
    assertThat(guard).isNotNull();

    guard.close();
    guard.close(); // 幂等：只删一次

    verify(zSetOperations)
        .remove(eq(RedisConcurrencyLimiter.KEY_PREFIX + "full"), anyString());
  }
}
