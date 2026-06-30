package com.ragforge.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.ragforge.common.Result;
import com.ragforge.config.ApiKeyProperties;
import com.ragforge.web.TraceIds;
import com.ragforge.mapper.ApiKeyMapper;
import com.ragforge.model.entity.ApiKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyInterceptor implements HandlerInterceptor {

  private static final String CONTEXT_SET_ATTR = ApiKeyInterceptor.class.getName() + ".contextSet";
  static final String RATE_LIMIT_PREFIX = "ragforge:ratelimit:";

  private final ApiKeyMapper apiKeyMapper;
  private final ApiKeyProperties apiKeyProperties;
  private final ObjectMapper objectMapper;
  private final StringRedisTemplate redisTemplate;
  private final List<DevApiKeyConfig> devApiKeyConfigs;

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    String path = request.getRequestURI();
    if (isWhitelisted(path)) {
      return true;
    }
    if (RagAuthContextHolder.get() != null) {
      return true;
    }

    String apiKey = request.getHeader(apiKeyProperties.getHeader());
    DevApiKeyConfig devApiKey = findDevApiKey(apiKey);
    if (devApiKey != null) {
      installContext(request, devApiKey.context(), apiKey);
      return true;
    }
    if (DevApiKeyConfig.DEV_KEY.equals(apiKey)) {
      writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, 401, "Invalid API Key");
      return false;
    }
    ApiKey keyRecord = apiKey != null ? findValidApiKey(apiKey) : null;
    if (keyRecord != null) {
      if (!consumeRateLimit(keyRecord)) {
        writeJsonError(response, 429, 429, "API Key rate limit exceeded");
        return false;
      }
      installContext(request, contextFrom(keyRecord), apiKey);
      // key 绑定组织：以 key 的 org_id 作为组织上下文（权威，外部调用无需也不应传 X-Org-Id）。
      if (keyRecord.getOrgId() != null) {
        OrgContextHolder.set(keyRecord.getOrgId());
      }
      touchLastUsed(keyRecord);
      return true;
    }

    writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, 401, "Invalid API Key");
    return false;
  }

  /** 更新 last_used_at（节流：每把 key 最多每 60s 写一次，避免热点路径频繁写库）。 */
  private void touchLastUsed(ApiKey keyRecord) {
    try {
      java.time.LocalDateTime now = java.time.LocalDateTime.now();
      java.time.LocalDateTime last = keyRecord.getLastUsedAt();
      if (last != null && last.isAfter(now.minusSeconds(60))) {
        return;
      }
      ApiKey patch = new ApiKey();
      patch.setId(keyRecord.getId());
      patch.setLastUsedAt(now);
      apiKeyMapper.updateById(patch);
      keyRecord.setLastUsedAt(now);
    } catch (Exception ignore) {
      // 更新失败不影响主流程
    }
  }

  private void writeJsonError(HttpServletResponse response, int httpStatus, int code, String msg)
      throws IOException {
    response.setStatus(httpStatus);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    String traceId = TraceIds.current();
    String requestId = TraceIds.currentRequestId();
    response.setHeader(TraceIds.HEADER_TRACE_ID, traceId);
    response.setHeader(TraceIds.HEADER_REQUEST_ID, requestId);
    objectMapper.writeValue(response.getWriter(), Result.fail(code, msg));
  }

  private boolean isWhitelisted(String path) {
    return apiKeyProperties.getWhitelistPaths().stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
  }

  /**
   * 当 API Key 被创建或删除时调用，使缓存失效，下次请求重新查询数据库。
   */
  public void resetKeyCache() {
    // Kept for ApiKeyService compatibility. API keys are queried per request; there is no auth cache.
  }

  private ApiKey findValidApiKey(String apiKey) {
    return apiKeyMapper.selectOne(
        new LambdaQueryWrapper<ApiKey>()
            .eq(ApiKey::getApiKey, apiKey)
            .eq(ApiKey::getEnabled, true)
            .last("LIMIT 1"));
  }

  private DevApiKeyConfig findDevApiKey(String apiKey) {
    if (apiKey == null) {
      return null;
    }
    return devApiKeyConfigs.stream().filter(config -> config.matches(apiKey)).findFirst().orElse(null);
  }

  private RagAuthContext contextFrom(ApiKey apiKey) {
    String principalType = normalizePrincipalType(apiKey.getPrincipalType());
    String principalId = hasText(apiKey.getPrincipalId()) ? apiKey.getPrincipalId() : "sa:" + apiKey.getKeyName();
    Set<Long> allowedKbIds = longSet(apiKey.getAllowedKbIds());
    return new RagAuthContext(
        null,
        "SERVICE_ACCOUNT",
        allowedKbIds,
        allowedKbIds,
        stringSet(apiKey.getScopes()),
        principalType,
        principalId);
  }

  private void installContext(HttpServletRequest request, RagAuthContext context, String apiKey) {
    RagAuthContextHolder.set(context);
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
            context,
            apiKey,
            List.of(new SimpleGrantedAuthority("ROLE_" + context.ragRole())));
    authentication.setDetails(CONTEXT_SET_ATTR);
    SecurityContextHolder.getContext().setAuthentication(authentication);
    request.setAttribute(CONTEXT_SET_ATTR, Boolean.TRUE);
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    boolean apiKeyContext =
        Boolean.TRUE.equals(request.getAttribute(CONTEXT_SET_ATTR))
            || (authentication != null && CONTEXT_SET_ATTR.equals(authentication.getDetails()));
    if (apiKeyContext) {
      RagAuthContextHolder.clear();
      OrgContextHolder.clear();
      SecurityContextHolder.clearContext();
    }
  }

  private Set<String> stringSet(String json) {
    if (!hasText(json)) {
      return Set.of();
    }
    try {
      return new LinkedHashSet<>(objectMapper.readValue(json, new TypeReference<List<String>>() {}));
    } catch (Exception ex) {
      log.warn("Invalid api_keys.scopes JSON, using empty scopes");
      return Set.of();
    }
  }

  private Set<Long> longSet(String json) {
    if (!hasText(json)) {
      return Set.of();
    }
    try {
      List<Object> values = objectMapper.readValue(json, new TypeReference<>() {});
      Set<Long> result = new LinkedHashSet<>();
      for (Object value : values) {
        if (value instanceof Number number) {
          result.add(number.longValue());
        } else if (value != null) {
          result.add(Long.valueOf(String.valueOf(value)));
        }
      }
      return result;
    } catch (Exception ex) {
      log.warn("Invalid api_keys.allowed_kb_ids JSON, using empty KB set");
      return Set.of();
    }
  }

  private String normalizePrincipalType(String value) {
    if (!hasText(value) || "service".equals(value)) {
      return "service_account";
    }
    return value;
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
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
