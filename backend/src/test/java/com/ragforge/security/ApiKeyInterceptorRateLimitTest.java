package com.ragforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.config.ApiKeyProperties;
import com.ragforge.mapper.ApiKeyMapper;
import com.ragforge.model.entity.ApiKey;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ApiKeyInterceptorRateLimitTest {

  private ApiKeyInterceptor newInterceptor(StringRedisTemplate redis) {
    return new ApiKeyInterceptor(
        mock(ApiKeyMapper.class), new ApiKeyProperties(), new ObjectMapper(), redis);
  }

  private ApiKey keyWithLimit(int limit) {
    ApiKey k = new ApiKey();
    k.setApiKey("sk-test");
    k.setRateLimit(limit);
    return k;
  }

  @Test
  void allowsWhenUnderLimit() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.increment(anyString())).thenReturn(5L);
    assertThat(newInterceptor(redis).consumeRateLimit(keyWithLimit(100))).isTrue();
  }

  @Test
  void rejectsWhenOverLimit() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.increment(anyString())).thenReturn(101L);
    assertThat(newInterceptor(redis).consumeRateLimit(keyWithLimit(100))).isFalse();
  }

  @Test
  void failOpenWhenRedisThrows() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));
    assertThat(newInterceptor(redis).consumeRateLimit(keyWithLimit(100))).isTrue();
  }
}
