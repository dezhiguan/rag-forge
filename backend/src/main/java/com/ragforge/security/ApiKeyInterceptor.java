package com.ragforge.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.ragforge.common.ErrorMessages;
import com.ragforge.common.Result;
import com.ragforge.config.ApiKeyProperties;
import com.ragforge.web.TraceIds;
import com.ragforge.mapper.ApiKeyMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.entity.ApiKey;
import com.ragforge.model.entity.KnowledgeBase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
  private final KnowledgeBaseMapper knowledgeBaseMapper;

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
      writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, 401, "API_KEY_INVALID");
      return false;
    }
    ApiKey keyRecord = apiKey != null ? findValidApiKey(apiKey) : null;
    if (keyRecord != null) {
      // 过期校验：expires_at 非空且已过 → 拒绝。
      if (keyRecord.getExpiresAt() != null
          && keyRecord.getExpiresAt().isBefore(LocalDateTime.now())) {
        writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, 401, "API_KEY_EXPIRED");
        return false;
      }
      if (!consumeRateLimit(keyRecord)) {
        writeJsonError(response, 429, 429, "API_KEY_RATE_LIMITED");
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

    // 缺失与无效分别给码：缺 key → 引导携带；有 key 但查不到/被禁用/乱码 → 无效（不泄漏细节）。
    String errorCode = (apiKey == null || apiKey.isBlank()) ? "API_KEY_MISSING" : "API_KEY_INVALID";
    writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, 401, errorCode);
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

  /**
   * 统一鉴权失败响应：机器码放 errorCode，msg 翻成友好中文（外部直连也可读）。前端按 errorCode 分支、展示 msg。
   */
  private void writeJsonError(HttpServletResponse response, int httpStatus, int code, String errorCode)
      throws IOException {
    response.setStatus(httpStatus);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    String traceId = TraceIds.current();
    String requestId = TraceIds.currentRequestId();
    response.setHeader(TraceIds.HEADER_TRACE_ID, traceId);
    response.setHeader(TraceIds.HEADER_REQUEST_ID, requestId);
    objectMapper.writeValue(
        response.getWriter(), Result.error(code, errorCode, ErrorMessages.toChinese(errorCode)));
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
    // hash 优先：按 SHA-256(key) 命中；未命中回退明文（兼容尚未回填 hash 的存量 key）。
    String hash = sha256Hex(apiKey);
    ApiKey byHash =
        apiKeyMapper.selectOne(
            new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getKeyHash, hash)
                .eq(ApiKey::getEnabled, true)
                .last("LIMIT 1"));
    if (byHash != null) {
      return byHash;
    }
    return apiKeyMapper.selectOne(
        new LambdaQueryWrapper<ApiKey>()
            .eq(ApiKey::getApiKey, apiKey)
            .eq(ApiKey::getEnabled, true)
            .last("LIMIT 1"));
  }

  static String sha256Hex(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(64);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      return "";
    }
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
    Set<Long> readable = resolveReadableKbIds(apiKey);
    // 本期只做 READ：写集为空（无写接口接受 key）。READ_WRITE 开放后再据 access_level 赋值。
    Set<Long> writable = Set.of();
    return new RagAuthContext(
        null,
        "SERVICE_ACCOUNT",
        readable,
        writable,
        stringSet(apiKey.getScopes()),
        principalType,
        principalId);
  }

  /** 按 scope_mode 解析 key 可读的 KB 集合。ORG_ALL=本组织全部非删除库；KB_LIST=授权白名单。 */
  private Set<Long> resolveReadableKbIds(ApiKey apiKey) {
    String scopeMode = apiKey.getScopeMode();
    if ("KB_LIST".equals(scopeMode)) {
      return longSet(apiKey.getAllowedKbIds());
    }
    // ORG_ALL（默认）：本组织下的全部非删除库。
    Long orgId = apiKey.getOrgId();
    if (orgId == null) {
      return Set.of();
    }
    try {
      List<KnowledgeBase> kbs =
          knowledgeBaseMapper.selectList(
              new LambdaQueryWrapper<KnowledgeBase>()
                  .eq(KnowledgeBase::getOrgId, orgId)
                  .ne(KnowledgeBase::getStatus, "deleted")
                  .select(KnowledgeBase::getId));
      return kbs.stream().map(KnowledgeBase::getId).collect(Collectors.toCollection(LinkedHashSet::new));
    } catch (Exception e) {
      log.warn("resolveReadableKbIds(ORG_ALL) failed for org {}, using empty set", orgId, e);
      return Set.of();
    }
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
    // 用 key id 作计数键（稳定、不落明文）。
    String key = RATE_LIMIT_PREFIX + apiKey.getId() + ":" + nowMinute;
    try {
      Long count = redisTemplate.opsForValue().increment(key);
      if (count != null && count == 1L) {
        redisTemplate.expire(key, Duration.ofSeconds(120));
      }
      return count == null || count <= limit;
    } catch (Exception e) {
      // fail-closed：Redis 异常时拒绝，避免限流失效被绕过（D-E 定稿）。
      log.warn("Rate limit check via Redis failed, rejecting request (fail-closed)", e);
      return false;
    }
  }
}
