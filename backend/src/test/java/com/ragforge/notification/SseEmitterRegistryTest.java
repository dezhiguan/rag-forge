package com.ragforge.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseEmitterRegistryTest {

  private final SseEmitterRegistry registry = new SseEmitterRegistry();

  @Test
  void register_tracksConnectionPerUser() {
    registry.register(1L, 10_000L);
    registry.register(1L, 10_000L);
    registry.register(2L, 10_000L);

    assertThat(registry.connectionCount(1L)).isEqualTo(2);
    assertThat(registry.connectionCount(2L)).isEqualTo(1);
    assertThat(registry.onlineUserCount()).isEqualTo(2);
  }

  @Test
  void connectionCount_unknownUser_isZero() {
    assertThat(registry.connectionCount(99L)).isZero();
  }

  @Test
  void sendUnread_deliversToBoundEmitter() throws IOException {
    SseEmitter emitter = mock(SseEmitter.class);
    registry.bind(5L, emitter);

    registry.sendUnread(5L, 3L);

    verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
  }

  @Test
  void sendUnread_removesEmitterWhenSendFails() throws IOException {
    SseEmitter emitter = mock(SseEmitter.class);
    doThrow(new IOException("broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
    registry.bind(6L, emitter);

    registry.sendUnread(6L, 1L);

    assertThat(registry.connectionCount(6L)).isZero();
    assertThat(registry.onlineUserCount()).isZero();
  }

  @Test
  void sendUnread_unknownUser_isNoOp() {
    registry.sendUnread(123L, 9L);

    assertThat(registry.onlineUserCount()).isZero();
  }

  @Test
  void heartbeat_pingsEmittersAndKeepsHealthyOnes() throws IOException {
    SseEmitter emitter = mock(SseEmitter.class);
    registry.bind(7L, emitter);

    registry.heartbeat();

    verify(emitter, atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
    assertThat(registry.connectionCount(7L)).isEqualTo(1);
  }

  @Test
  void heartbeat_removesDeadEmitter() throws IOException {
    SseEmitter emitter = mock(SseEmitter.class);
    doThrow(new IOException("closed")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
    registry.bind(8L, emitter);

    registry.heartbeat();

    assertThat(registry.connectionCount(8L)).isZero();
  }
}
