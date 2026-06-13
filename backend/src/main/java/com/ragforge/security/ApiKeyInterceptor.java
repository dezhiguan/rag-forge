package com.ragforge.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.Result;
import com.ragforge.config.ApiKeyProperties;
import com.ragforge.mapper.ApiKeyMapper;
import com.ragforge.model.entity.ApiKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyInterceptor implements HandlerInterceptor {

  private static final String DEV_KEY = "sk-ragforge-dev";
  static final String RATE_LIMIT_PREFIX = "ragforge:ratelimit:";

  private final ApiKeyMapper apiKeyMapper;
  private final ApiKeyProperties apiKeyProperties;
  private final ObjectMapper objectMapper;
  private final StringRedisTemplate redisTemplate;

  private volatile Boolean hasAnyKeys;

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    String path = request.getRequestURI();
    if (isWhitelisted(path)) {
      return true;
    }

    // 首次启动时若数据库中没有任何 Key，放行所有请求，避免管理后台无法使用
    if (noKeysYet()) {
      return true;
    }

    String apiKey = request.getHeader(apiKeyProperties.getHeader());
    // 开发环境默认 key 始终有效，不受数据库状态影响
    if (DEV_KEY.equals(apiKey)) {
      return true;
    }
    ApiKey keyRecord = apiKey != null ? findValidApiKey(apiKey) : null;
    if (keyRecord != null) {
      if (!consumeRateLimit(keyRecord)) {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Result.fail(429, "API Key rate limit exceeded"));
        return false;
      }
      return true;
    }

    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(response.getWriter(), Result.fail(401, "Invalid API Key"));
    return false;
  }

  private boolean isWhitelisted(String path) {
    return apiKeyProperties.getWhitelistPaths().stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
  }

  private boolean noKeysYet() {
    if (hasAnyKeys == null) {
      synchronized (this) {
        if (hasAnyKeys == null) {
          Long count = apiKeyMapper.selectCount(null);
          hasAnyKeys = count != null && count > 0;
        }
      }
    }
    return !hasAnyKeys;
  }

  /**
   * 当 API Key 被创建或删除时调用，使缓存失效，下次请求重新查询数据库。
   */
  public void resetKeyCache() {
    synchronized (this) {
      hasAnyKeys = null;
    }
  }

  private ApiKey findValidApiKey(String apiKey) {
    return apiKeyMapper.selectOne(
        new LambdaQueryWrapper<ApiKey>()
            .eq(ApiKey::getApiKey, apiKey)
            .eq(ApiKey::getEnabled, true)
            .last("LIMIT 1"));
  }

  boolean consumeRateLimit(ApiKey apiKey) {
    int limit = apiKey.getRateLimit() == null ? 100 : apiKey.getRateLimit();
    if (limit <= 0) {
      return false;
    }
    long nowMinute = System.currentTimeMillis() / 60_000L;
    String key = RATE_LIMIT_PREFIX + apiKey.getApiKey() + ":" + nowMinute;
    try {
      Long count = redisTemplate.opsForValue().increment(key);
      if (count != null && count == 1L) {
        redisTemplate.expire(key, Duration.ofSeconds(120));
      }
      return count == null || count <= limit;
    } catch (Exception e) {
      // fail-open：Redis 异常时不阻断业务，放行并告警，避免限流组件拖垮整个 API
      log.warn("Rate limit check via Redis failed, allowing request", e);
      return true;
    }
  }
}
