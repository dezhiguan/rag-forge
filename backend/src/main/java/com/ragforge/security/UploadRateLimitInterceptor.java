package com.ragforge.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.ErrorMessages;
import com.ragforge.common.Result;
import com.ragforge.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 上传链路（presign / register）的用户级限流。
 *
 * <p>背景：这两个端点不在 {@link ApiKeyInterceptor} 的拦截路径里，且 JWT 已认证请求会被其直接放行，
 * 此前唯一的约束是前端写死的并发数，失控/恶意客户端可无限制打 DB 与 OSS。
 *
 * <p>口径：固定窗口，按「主体 + 端点 + 分钟」计数；对人宽松（默认 120/min/端点，高于前端批量上传的
 * 实际峰值 ~90/min），对脚本失控有效。上传链路本身依赖 Redis（uploadToken 签发/消费），
 * 故与 ApiKeyInterceptor 同样采用 fail-closed：Redis 异常时拒绝，避免限流被绕过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadRateLimitInterceptor implements HandlerInterceptor {

  static final String RATE_LIMIT_PREFIX = "ragforge:ratelimit:upload:";

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  /** 每分钟每端点上限；<=0 表示关闭限流。 */
  @Value("${ragforge.upload.rate-limit-per-minute:120}")
  private int limitPerMinute;

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    if (limitPerMinute <= 0) {
      return true;
    }
    String subject = resolveSubject(request);
    long nowMinute = System.currentTimeMillis() / 60_000L;
    String key = RATE_LIMIT_PREFIX + subject + ":" + request.getRequestURI() + ":" + nowMinute;
    try {
      Long count = redisTemplate.opsForValue().increment(key);
      if (count != null && count == 1L) {
        redisTemplate.expire(key, Duration.ofSeconds(120));
      }
      if (count != null && count > limitPerMinute) {
        writeJsonError(response, 429, 429, "UPLOAD_RATE_LIMITED");
        return false;
      }
      return true;
    } catch (Exception e) {
      // fail-closed：与 ApiKeyInterceptor 口径一致；上传链路本身依赖 Redis，此处放行也无意义。
      log.warn("Upload rate limit check via Redis failed, rejecting request (fail-closed)", e);
      writeJsonError(response, 429, 429, "UPLOAD_RATE_LIMITED");
      return false;
    }
  }

  /** 计数主体：优先认证上下文（userId / principalId），匿名请求退化为客户端 IP（随后会被鉴权拒绝）。 */
  private String resolveSubject(HttpServletRequest request) {
    RagAuthContext ctx = RagAuthContextHolder.get();
    if (ctx != null && ctx.userId() != null) {
      return "u:" + ctx.userId();
    }
    if (ctx != null && ctx.principalId() != null) {
      return "p:" + ctx.principalId();
    }
    return "ip:" + request.getRemoteAddr();
  }

  private void writeJsonError(HttpServletResponse response, int httpStatus, int code, String errorCode)
      throws IOException {
    response.setStatus(httpStatus);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.setHeader(TraceIds.HEADER_TRACE_ID, TraceIds.current());
    response.setHeader(TraceIds.HEADER_REQUEST_ID, TraceIds.currentRequestId());
    objectMapper.writeValue(
        response.getWriter(), Result.error(code, errorCode, ErrorMessages.toChinese(errorCode)));
  }
}
