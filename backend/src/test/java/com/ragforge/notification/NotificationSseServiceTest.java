package com.ragforge.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.common.BizException;
import com.ragforge.mapper.NotificationMapper;
import com.ragforge.security.JwtClaims;
import com.ragforge.security.JwtVerifier;
import com.ragforge.security.RagAuthContext;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class NotificationSseServiceTest {

  @Mock private JwtVerifier jwtVerifier;
  @Mock private SseEmitterRegistry registry;
  @Mock private NotificationMapper notificationMapper;

  @InjectMocks private NotificationSseService service;

  private static RagAuthContext ctx(Long userId) {
    return new RagAuthContext(userId, "USER", Set.of(), Set.of(), Set.of(), "USER", String.valueOf(userId));
  }

  @Test
  void subscribe_validToken_registersAndSendsInitialUnread() throws IOException {
    JwtClaims claims = new JwtClaims(Map.of());
    SseEmitter emitter = mock(SseEmitter.class);
    when(jwtVerifier.verify("good")).thenReturn(claims);
    when(jwtVerifier.toContext(claims)).thenReturn(ctx(9L));
    when(registry.register(9L, NotificationSseService.EMITTER_TIMEOUT_MS)).thenReturn(emitter);
    when(notificationMapper.selectCount(any())).thenReturn(2L);

    SseEmitter result = service.subscribe("good");

    assertThat(result).isSameAs(emitter);
    verify(registry).register(eq(9L), eq(NotificationSseService.EMITTER_TIMEOUT_MS));
    verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
  }

  @Test
  void subscribe_nullCount_sendsZeroAndStillRegisters() throws IOException {
    JwtClaims claims = new JwtClaims(Map.of());
    SseEmitter emitter = mock(SseEmitter.class);
    when(jwtVerifier.verify("good")).thenReturn(claims);
    when(jwtVerifier.toContext(claims)).thenReturn(ctx(10L));
    when(registry.register(10L, NotificationSseService.EMITTER_TIMEOUT_MS)).thenReturn(emitter);
    when(notificationMapper.selectCount(any())).thenReturn(null);

    service.subscribe("good");

    verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
  }

  @Test
  void subscribe_blankToken_throws401() {
    assertThatThrownBy(() -> service.subscribe("  "))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(401));
    verify(registry, never()).register(any(), org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void subscribe_nullToken_throws401() {
    assertThatThrownBy(() -> service.subscribe(null))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(401));
  }

  @Test
  void subscribe_invalidToken_throws401() {
    when(jwtVerifier.verify("bad")).thenThrow(new RuntimeException("bad signature"));

    assertThatThrownBy(() -> service.subscribe("bad"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(401));
  }

  @Test
  void subscribe_contextWithoutUserId_throws401() {
    JwtClaims claims = new JwtClaims(Map.of());
    when(jwtVerifier.verify("good")).thenReturn(claims);
    when(jwtVerifier.toContext(claims)).thenReturn(ctx(null));

    assertThatThrownBy(() -> service.subscribe("good"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(401));
  }
}
