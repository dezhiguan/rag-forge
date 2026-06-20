package com.ragforge.service.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.service.upload.UploadTokenService.TokenPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UploadTokenServiceTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private UploadTokenService uploadTokenService;

  @BeforeEach
  void setUp() {
    uploadTokenService = new UploadTokenService(redisTemplate, objectMapper);
    ReflectionTestUtils.setField(uploadTokenService, "hmacSecret", "unit-test-secret");
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
  }

  @Test
  void issueStoresSignedTokenPayloadWithTtlAndConsumeUsesGetDel() throws Exception {
    TokenPayload payload = payload();
    ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);

    String token = uploadTokenService.issue(payload);

    verify(valueOperations)
        .set(
            eq("ragforge:upload:token:" + token),
            jsonCaptor.capture(),
            eq(UploadTokenService.TOKEN_TTL));
    assertThat(token).startsWith("uplt_");
    assertThat(objectMapper.readValue(jsonCaptor.getValue(), TokenPayload.class).getUploadToken())
        .isEqualTo(token);

    when(valueOperations.getAndDelete(tokenCaptor.capture())).thenReturn(jsonCaptor.getValue());
    TokenPayload consumed = uploadTokenService.consume(token);

    assertThat(tokenCaptor.getValue()).isEqualTo("ragforge:upload:token:" + token);
    assertThat(consumed.getKbId()).isEqualTo(16L);
    assertThat(consumed.getTenantId()).isEqualTo("tn_test");
  }

  @Test
  void consumeMissingTokenThrowsTokenInvalid() {
    when(valueOperations.getAndDelete(any())).thenReturn(null);

    assertThatThrownBy(() -> uploadTokenService.consume("uplt_missing"))
        .isInstanceOf(BizException.class)
        .hasMessage("TOKEN_INVALID");
  }

  @Test
  void consumeTamperedTokenThrowsTokenInvalidAfterGetDel() throws Exception {
    TokenPayload payload = payload();
    String token = uploadTokenService.issue(payload);
    String stored = objectMapper.writeValueAsString(payload);
    when(valueOperations.getAndDelete("ragforge:upload:token:" + token + "x")).thenReturn(stored);

    assertThatThrownBy(() -> uploadTokenService.consume(token + "x"))
        .isInstanceOf(BizException.class)
        .hasMessage("TOKEN_INVALID");
  }

  private TokenPayload payload() {
    TokenPayload payload = new TokenPayload();
    payload.setTenantId("tn_test");
    payload.setKbId(16L);
    payload.setStorageBucket("ragforge-dev");
    payload.setStorageKey("tn_test/kb_16/uplt_a/big.pdf");
    payload.setFilename("big.pdf");
    payload.setContentType("application/pdf");
    payload.setDeclaredSize(83886080L);
    return payload;
  }
}
