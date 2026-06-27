package com.ragforge.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.mapper.RevokedJtiMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;

@ExtendWith(MockitoExtension.class)
class AuthEventServiceTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;
  @Mock private RevokedJtiMapper revokedJtiMapper;

  private AuthEventProperties properties;
  private AuthEventService service;

  @BeforeEach
  void setUp() {
    properties = new AuthEventProperties();
    properties.setHmacSecret("event-secret");
    service = new AuthEventService(properties, new ObjectMapper(), redisTemplate, revokedJtiMapper);
  }

  @Test
  void invalidSignatureDoesNotTouchRedis() {
    String body = "{\"event_id\":\"evt-1\",\"type\":\"session.revoked\",\"jti\":\"jti-1\"}";
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Auth-Event-Signature", "sha256=bad");
    headers.set("X-Auth-Event-Timestamp", String.valueOf(Instant.now().getEpochSecond()));

    AuthEventResult result = service.handle("session.revoked", body, headers);

    assertThat(result.status()).isEqualTo(401);
    verify(redisTemplate, never()).opsForValue();
  }

  @Test
  void validSessionRevokedEventWritesJtiBlacklistAfterIdempotency() {
    String body = "{\"event_id\":\"evt-1\",\"type\":\"session.revoked\",\"jti\":\"jti-1\",\"exp\":4102444800}";
    when(redisTemplate.hasKey(AuthEventService.EVENT_ID_PREFIX + "evt-1")).thenReturn(false);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    AuthEventResult result = service.handle("session.revoked", body, signedHeaders(body));

    assertThat(result.status()).isEqualTo(200);
    assertThat(result.revokedJtiCount()).isEqualTo(1);
    verify(valueOperations).set(eq(AuthEventService.REVOKED_JTI_PREFIX + "jti-1"), eq("evt-1"), any(Duration.class));
    verify(valueOperations).set(eq(AuthEventService.EVENT_ID_PREFIX + "evt-1"), eq("session.revoked"), any(Duration.class));
  }

  @Test
  void validSignatureWithExpiredTimestampIsRejected() {
    String body = "{\"event_id\":\"evt-old\",\"type\":\"session.revoked\",\"jti\":\"jti-old\"}";
    HttpHeaders headers = signedHeaders(body);
    headers.set("X-Auth-Event-Timestamp", String.valueOf(Instant.now().minusSeconds(600).getEpochSecond()));

    AuthEventResult result = service.handle("session.revoked", body, headers);

    assertThat(result.status()).isEqualTo(401);
    verify(redisTemplate, never()).opsForValue();
  }

  @Test
  void legacyHubSignatureHeaderIsRejected() {
    String body = "{\"event_id\":\"evt-legacy\",\"type\":\"session.revoked\",\"jti\":\"jti-legacy\"}";
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Hub-Signature-256", "sha256=" + hmac(body));
    headers.set("X-Auth-Event-Timestamp", String.valueOf(Instant.now().getEpochSecond()));

    AuthEventResult result = service.handle("session.revoked", body, headers);

    assertThat(result.status()).isEqualTo(401);
    verify(redisTemplate, never()).opsForValue();
  }

  @Test
  void duplicateEventDoesNotRewriteBlacklist() {
    String body = "{\"event_id\":\"evt-1\",\"type\":\"session.revoked\",\"jti\":\"jti-1\"}";
    when(redisTemplate.hasKey(AuthEventService.EVENT_ID_PREFIX + "evt-1")).thenReturn(true);

    AuthEventResult result = service.handle("session.revoked", body, signedHeaders(body));

    assertThat(result.message()).isEqualTo("duplicate");
    verify(valueOperations, never()).set(eq(AuthEventService.REVOKED_JTI_PREFIX + "jti-1"), anyString(), any(Duration.class));
  }

  @Test
  void passwordChangedStoresUserRevocationWatermark() {
    String body = "{\"event_id\":\"evt-2\",\"type\":\"user.password.changed\",\"user_id\":\"42\",\"occurred_at\":1234}";
    when(redisTemplate.hasKey(AuthEventService.EVENT_ID_PREFIX + "evt-2")).thenReturn(false);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    AuthEventResult result = service.handle("user.password.changed", body, signedHeaders(body));

    assertThat(result.status()).isEqualTo(200);
    verify(valueOperations)
        .set(eq(AuthEventService.USER_REVOKED_AFTER_PREFIX + "42"), eq("1234"), any(Duration.class));
  }

  @Test
  void passwordChangedFromAuthGatewayEnvelopeRevokesUserJwt() {
    String occurredAt = "2026-06-20T09:00:00Z";
    String body =
        "{\"event_id\":\"evt-gw-1\",\"event_type\":\"user.password.changed\","
            + "\"occurred_at\":\""
            + occurredAt
            + "\",\"payload\":{\"user_id\":42}}";
    when(redisTemplate.hasKey(AuthEventService.EVENT_ID_PREFIX + "evt-gw-1")).thenReturn(false);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    AuthEventResult result = service.handle("user.password.changed", body, signedHeaders(body));

    assertThat(result.status()).isEqualTo(200);
    verify(valueOperations)
        .set(
            eq(AuthEventService.USER_REVOKED_AFTER_PREFIX + "42"),
            eq(String.valueOf(Instant.parse(occurredAt).getEpochSecond())),
            any(Duration.class));
  }

  @Test
  void revokeAccessTokenJtiWritesBlacklistWithExpTtl() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    service.revokeAccessTokenJti("jti-logout", 4102444800L);

    verify(valueOperations)
        .set(eq(AuthEventService.REVOKED_JTI_PREFIX + "jti-logout"), eq("logout"), any(Duration.class));
    verify(revokedJtiMapper).revoke(eq("jti-logout"), any(), eq("logout"));
  }

  @Test
  void revokedJwtChecksDatabaseWhenRedisMisses() {
    when(redisTemplate.hasKey(AuthEventService.REVOKED_JTI_PREFIX + "jti-db")).thenReturn(false);
    when(revokedJtiMapper.isRevoked("jti-db")).thenReturn(true);

    assertThat(service.isJwtRevoked(new AuthJwtToken("jti-db", "42", 101L))).isTrue();
  }

  @Test
  void revokedJwtChecksJtiAndPasswordChangedWatermark() {
    when(redisTemplate.hasKey(AuthEventService.REVOKED_JTI_PREFIX + "jti-1")).thenReturn(false);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(AuthEventService.USER_REVOKED_AFTER_PREFIX + "42")).thenReturn("100");

    assertThat(service.isJwtRevoked(new AuthJwtToken("jti-1", "42", 99L))).isTrue();
    assertThat(service.isJwtRevoked(new AuthJwtToken("jti-1", "42", 101L))).isFalse();
  }

  @Test
  void passwordChangedWatermarkAppliesWhenJwtHasNoJti() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(AuthEventService.USER_REVOKED_AFTER_PREFIX + "42")).thenReturn("100");

    assertThat(service.isJwtRevoked(new AuthJwtToken(null, "42", 99L))).isTrue();
  }

  private HttpHeaders signedHeaders(String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Auth-Event-Signature", "sha256=" + hmac(body));
    headers.set("X-Auth-Event-Timestamp", String.valueOf(Instant.now().getEpochSecond()));
    return headers;
  }

  private String hmac(String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(properties.getHmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
