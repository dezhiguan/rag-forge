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

  private final ApiKeyMapper apiKeyMapper;
  private final ApiKeyProperties apiKeyProperties;
  private final ObjectMapper objectMapper;

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    String path = request.getRequestURI();
    if (isWhitelisted(path)) {
      return true;
    }

    String apiKey = request.getHeader(apiKeyProperties.getHeader());
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
