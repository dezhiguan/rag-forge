package com.ragforge.service.upload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UploadTokenService {

  public static final Duration TOKEN_TTL = Duration.ofMinutes(30);

  private static final String TOKEN_PREFIX = "uplt_";
  private static final String REDIS_PREFIX = "ragforge:upload:token:";

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  @Value("${ragforge.auth.events.hmac-secret:dev-secret-must-match-authgw}")
  private String hmacSecret;

  @Value("${ragforge.oss.upload.token-ttl-seconds:${ragforge.upload.token-ttl-seconds:1800}}")
  private long tokenTtlSeconds;

  public String issue(TokenPayload payload) {
    try {
      long now = Instant.now().getEpochSecond();
      Duration ttl = tokenTtl();
      payload.setIssuedAt(now);
      payload.setExpiresAt(now + ttl.toSeconds());
      String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
      String body = base64Url(objectMapper.writeValueAsBytes(payload));
      String signature = sign(header + "." + body);
      String token = TOKEN_PREFIX + header + "." + body + "." + signature;
      payload.setUploadToken(token);
      redisTemplate.opsForValue().set(redisKey(token), objectMapper.writeValueAsString(payload), ttl);
      return token;
    } catch (Exception e) {
      throw new BizException(500, "UPLOAD_TOKEN_ISSUE_FAILED");
    }
  }

  public Duration tokenTtl() {
    return tokenTtlSeconds > 0 ? Duration.ofSeconds(tokenTtlSeconds) : TOKEN_TTL;
  }

  public TokenPayload consume(String token) {
    String payloadJson = redisTemplate.opsForValue().getAndDelete(redisKey(token));
    if (payloadJson == null) {
      throw new BizException(409, "TOKEN_INVALID");
    }
    try {
      TokenPayload payload = objectMapper.readValue(payloadJson, TokenPayload.class);
      verify(token, payload);
      return payload;
    } catch (BizException e) {
      throw e;
    } catch (Exception e) {
      throw new BizException(409, "TOKEN_INVALID");
    }
  }

  private void verify(String token, TokenPayload payload) {
    if (token == null || !token.startsWith(TOKEN_PREFIX)) {
      throw new BizException(409, "TOKEN_INVALID");
    }
    if (!token.equals(payload.getUploadToken())) {
      throw new BizException(409, "TOKEN_INVALID");
    }
    String compact = token.substring(TOKEN_PREFIX.length());
    String[] parts = compact.split("\\.");
    if (parts.length != 3) {
      throw new BizException(409, "TOKEN_INVALID");
    }
    String expected = sign(parts[0] + "." + parts[1]);
    if (!constantTimeEquals(expected, parts[2])) {
      throw new BizException(409, "TOKEN_INVALID");
    }
    TokenPayload signedPayload = decodePayload(parts[1]);
    if (!sameSignedPayload(signedPayload, payload)) {
      throw new BizException(409, "TOKEN_INVALID");
    }
    if (payload.getExpiresAt() == null || payload.getExpiresAt() < Instant.now().getEpochSecond()) {
      throw new BizException(409, "TOKEN_INVALID");
    }
  }

  private TokenPayload decodePayload(String encoded) {
    try {
      return objectMapper.readValue(Base64.getUrlDecoder().decode(encoded), TokenPayload.class);
    } catch (Exception e) {
      throw new BizException(409, "TOKEN_INVALID");
    }
  }

  private static boolean sameSignedPayload(TokenPayload signed, TokenPayload stored) {
    return Objects.equals(signed.getKbId(), stored.getKbId())
        && Objects.equals(signed.getStorageBucket(), stored.getStorageBucket())
        && Objects.equals(signed.getStorageKey(), stored.getStorageKey())
        && Objects.equals(signed.getFilename(), stored.getFilename())
        && Objects.equals(signed.getContentType(), stored.getContentType())
        && Objects.equals(signed.getDeclaredSize(), stored.getDeclaredSize())
        && Objects.equals(signed.getIssuedAt(), stored.getIssuedAt())
        && Objects.equals(signed.getExpiresAt(), stored.getExpiresAt());
  }

  private String sign(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return base64Url(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new BizException(500, "UPLOAD_TOKEN_SIGN_FAILED");
    }
  }

  private static String redisKey(String token) {
    return REDIS_PREFIX + token;
  }

  private static String base64Url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  @Data
  public static class TokenPayload {
    private String uploadToken;
    private Long kbId;
    private String storageBucket;
    private String storageKey;
    private String filename;
    private String contentType;
    private Long declaredSize;
    private Long issuedAt;
    private Long expiresAt;
  }
}
