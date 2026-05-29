package com.ragforge.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.Result;
import com.ragforge.mapper.ApiKeyMapper;
import com.ragforge.model.entity.ApiKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class ApiKeyInterceptor implements HandlerInterceptor {

  private static final String DEV_KEY = "sk-ragforge-dev";

  private final ApiKeyMapper apiKeyMapper;
  private final ApiKeyProperties apiKeyProperties;
  private final ObjectMapper objectMapper;

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
    if (apiKey != null && isValidApiKey(apiKey)) {
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

  private boolean isValidApiKey(String apiKey) {
    ApiKey record =
        apiKeyMapper.selectOne(
            new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getApiKey, apiKey)
                .eq(ApiKey::getEnabled, true)
                .last("LIMIT 1"));
    return record != null;
  }
}
