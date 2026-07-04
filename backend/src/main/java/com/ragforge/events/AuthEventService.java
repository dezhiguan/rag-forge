package com.ragforge.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.mapper.RevokedJtiMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AuthEventService {

  public static final String REVOKED_JTI_PREFIX = "ragforge:auth:revoked:jti:";
  public static final String EVENT_ID_PREFIX = "ragforge:auth:event:";
  public static final String USER_REVOKED_AFTER_PREFIX = "ragforge:auth:revoked:user:";

  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private final AuthEventProperties properties;
  private final ObjectMapper objectMapper;
  private final StringRedisTemplate redisTemplate;
  private final RevokedJtiMapper revokedJtiMapper;

  public AuthEventResult handle(String expectedType, String rawBody, HttpHeaders headers) {
    if (!verifyTimestamp(headers)) {
      return AuthEventResult.invalidSignature();
    }
    if (!verifySignature(rawBody, headers)) {
      return AuthEventResult.invalidSignature();
    }

    AuthEventPayload payload = parse(rawBody);
    if (!StringUtils.hasText(payload.eventId())) {
      return AuthEventResult.badRequest("event_id required");
    }
    // 单个订阅 endpoint 会收到多种事件类型（如 session.revoked 与 user.password.changed
    // 都投到同一 URL），按 payload 自带的真实 type 分发，不因 URL 与 type 不一致而拒收，
    // 否则 user.password.changed 会被 session-revoked 端点误判 mismatch，改密吊销失效（SEC-04）。
    String effectiveType = StringUtils.hasText(payload.type()) ? payload.type() : expectedType;

    String eventKey = EVENT_ID_PREFIX + payload.eventId();
    if (Boolean.TRUE.equals(redisTemplate.hasKey(eventKey))) {
      return AuthEventResult.duplicate(payload.eventId());
    }

    int revokedCount = revokeTokens(payload);
    // 改密强制吊销该用户全部令牌；登出全部设备（session.revoked 携带 user_id 而非单个 jti）同理。
    // 单会话登出只带 jti（不带 user_id），由上面的 revokeTokens 按 jti 精确吊销，不会误伤其它会话。
    if ("user.password.changed".equals(effectiveType) || StringUtils.hasText(userKey(payload))) {
      revokeUser(payload);
    }
    redisTemplate.opsForValue().set(eventKey, effectiveType, properties.getIdempotencyTtl());

    return AuthEventResult.accepted(payload.eventId(), revokedCount);
  }

  /** Synchronously blacklist a JWT jti (e.g. RAG proxy logout) without waiting for auth-gateway webhook. */
  public void revokeAccessTokenJti(String jti, Long expEpochSeconds) {
    if (!StringUtils.hasText(jti)) {
      return;
    }
    revokeJti(jti, expEpochSeconds, "logout");
  }

  public boolean isJwtRevoked(AuthJwtToken token) {
    if (StringUtils.hasText(token.jti())) {
      if (isJtiRevokedInRedis(token.jti())) {
        return true;
      }
      if (isJtiRevokedInDatabase(token.jti())) {
        return true;
      }
    }
    if (StringUtils.hasText(token.userKey()) && token.issuedAtEpochSeconds() != null) {
      String revokedAfter = redisTemplate.opsForValue().get(USER_REVOKED_AFTER_PREFIX + token.userKey());
      if (StringUtils.hasText(revokedAfter)) {
        // 严格小于：iat 与 occurred_at 均为秒级，改密后网关在同一秒内就会签发新 token，
        // 包含式 <= 会把这枚新 token 误杀（偶发"登录已过期"），同秒 token 必须放行。
        return token.issuedAtEpochSeconds() < Long.parseLong(revokedAfter);
      }
    }
    return false;
  }

  private AuthEventPayload parse(String rawBody) {
    try {
      return objectMapper.readValue(rawBody, AuthEventPayload.class);
    } catch (Exception ex) {
      throw new IllegalArgumentException("invalid event payload", ex);
    }
  }

  private int revokeTokens(AuthEventPayload payload) {
    Set<String> jtis = jtis(payload);
    for (String jti : jtis) {
      revokeJti(jti, payload.exp(), payload.eventId());
    }
    return jtis.size();
  }

  private void revokeJti(String jti, Long expEpochSeconds, String source) {
    try {
      redisTemplate
          .opsForValue()
          .set(REVOKED_JTI_PREFIX + jti, source, ttlForExp(expEpochSeconds));
    } catch (RuntimeException ex) {
      // Redis may be unreachable in some prod topologies; PostgreSQL is the durable fallback.
    }
    Instant expiresAt =
        expEpochSeconds == null
            ? Instant.now().plus(properties.getRevokedJtiTtl())
            : Instant.ofEpochSecond(expEpochSeconds);
    revokedJtiMapper.revoke(jti, expiresAt, source);
  }

  private boolean isJtiRevokedInRedis(String jti) {
    try {
      return Boolean.TRUE.equals(redisTemplate.hasKey(REVOKED_JTI_PREFIX + jti));
    } catch (RuntimeException ex) {
      return false;
    }
  }

  private boolean isJtiRevokedInDatabase(String jti) {
    try {
      return revokedJtiMapper.isRevoked(jti);
    } catch (RuntimeException ex) {
      return false;
    }
  }

  private Set<String> jtis(AuthEventPayload payload) {
    Set<String> result = new LinkedHashSet<>();
    if (StringUtils.hasText(payload.jti())) {
      result.add(payload.jti());
    }
    if (payload.jtis() != null) {
      for (String jti : payload.jtis()) {
        if (StringUtils.hasText(jti)) {
          result.add(jti);
        }
      }
    }
    Object dataJti = payload.data() == null ? null : payload.data().get("jti");
    if (dataJti != null && StringUtils.hasText(String.valueOf(dataJti))) {
      result.add(String.valueOf(dataJti));
    }
    Object dataJtis = payload.data() == null ? null : payload.data().get("jtis");
    if (dataJtis instanceof Iterable<?> iterable) {
      for (Object item : iterable) {
        if (item != null && StringUtils.hasText(String.valueOf(item))) {
          result.add(String.valueOf(item));
        }
      }
    }
    return result;
  }

  private void revokeUser(AuthEventPayload payload) {
    String userKey = userKey(payload);
    if (!StringUtils.hasText(userKey)) {
      return;
    }
    long occurredAt = occurredAtEpochSeconds(payload);
    redisTemplate
        .opsForValue()
        .set(USER_REVOKED_AFTER_PREFIX + userKey, String.valueOf(occurredAt), properties.getUserRevocationTtl());
  }

  private long occurredAtEpochSeconds(AuthEventPayload payload) {
    Object value = payload.occurredAt();
    if (value == null) {
      return Instant.now().getEpochSecond();
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    String text = String.valueOf(value).trim();
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException ignored) {
      // Try ISO-8601 below.
    }
    try {
      return Instant.parse(text).getEpochSecond();
    } catch (RuntimeException ignored) {
      return Instant.now().getEpochSecond();
    }
  }

  private String userKey(AuthEventPayload payload) {
    if (StringUtils.hasText(payload.userId())) {
      return payload.userId();
    }
    if (StringUtils.hasText(payload.sub())) {
      return payload.sub();
    }
    Object dataUserId = payload.data() == null ? null : payload.data().get("user_id");
    return dataUserId == null ? null : String.valueOf(dataUserId);
  }

  private Duration ttlFor(AuthEventPayload payload) {
    return ttlForExp(payload.exp());
  }

  private Duration ttlForExp(Long expEpochSeconds) {
    if (expEpochSeconds == null) {
      return properties.getRevokedJtiTtl();
    }
    long seconds = expEpochSeconds - Instant.now().getEpochSecond();
    return seconds > 0 ? Duration.ofSeconds(seconds) : Duration.ofSeconds(1);
  }

  private boolean verifySignature(String rawBody, HttpHeaders headers) {
    String provided = signature(headers);
    if (!StringUtils.hasText(provided) || !StringUtils.hasText(properties.getHmacSecret())) {
      return false;
    }
    String expected = hmac(rawBody);
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII),
        normalizeSignature(provided).getBytes(StandardCharsets.US_ASCII));
  }

  private boolean verifyTimestamp(HttpHeaders headers) {
    String timestamp = headers.getFirst(properties.getTimestampHeader());
    if (!StringUtils.hasText(timestamp)) {
      return false;
    }
    try {
      long eventEpochSeconds = Long.parseLong(timestamp.trim());
      long nowEpochSeconds = Instant.now().getEpochSecond();
      long allowedSkewSeconds = properties.getMaxClockSkew().toSeconds();
      return Math.abs(nowEpochSeconds - eventEpochSeconds) <= allowedSkewSeconds;
    } catch (NumberFormatException ex) {
      return false;
    }
  }

  private String signature(HttpHeaders headers) {
    return headers.getFirst(properties.getSignatureHeader());
  }

  private String normalizeSignature(String signature) {
    String value = signature.trim();
    int equals = value.indexOf('=');
    if (equals >= 0) {
      value = value.substring(equals + 1);
    }
    return value.toLowerCase(Locale.ROOT);
  }

  private String hmac(String rawBody) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(properties.getHmacSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      byte[] digest = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (Exception ex) {
      throw new IllegalStateException("failed to calculate auth event HMAC", ex);
    }
  }
}
