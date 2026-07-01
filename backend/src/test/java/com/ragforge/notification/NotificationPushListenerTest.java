package com.ragforge.notification;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;

@ExtendWith(MockitoExtension.class)
class NotificationPushListenerTest {

  @Mock private SseEmitterRegistry registry;

  @InjectMocks private NotificationPushListener listener;

  private static DefaultMessage msg(String body) {
    return new DefaultMessage(
        NotificationPusher.CHANNEL.getBytes(StandardCharsets.UTF_8),
        body.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void onMessage_parsesUserIdAndCountAndDelivers() {
    listener.onMessage(msg("88:3"), null);

    verify(registry).sendUnread(88L, 3L);
  }

  @Test
  void onMessage_ignoresPayloadWithoutColon() {
    listener.onMessage(msg("abc"), null);

    verify(registry, never()).sendUnread(anyLong(), anyLong());
  }

  @Test
  void onMessage_ignoresNonNumericPayload() {
    listener.onMessage(msg("x:y"), null);

    verify(registry, never()).sendUnread(anyLong(), anyLong());
  }

  @Test
  void onMessage_ignoresTrailingColonPayload() {
    listener.onMessage(msg("88:"), null);

    verify(registry, never()).sendUnread(anyLong(), anyLong());
  }
}
