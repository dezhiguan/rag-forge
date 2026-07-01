package com.ragforge.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.mapper.NotificationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class NotificationPusherTest {

  @Mock private NotificationMapper notificationMapper;
  @Mock private StringRedisTemplate redisTemplate;
  @Mock private SseEmitterRegistry registry;

  @InjectMocks private NotificationPusher pusher;

  @Test
  void pushUnread_publishesUserIdAndCountToRedis() {
    when(notificationMapper.selectCount(any())).thenReturn(3L);

    pusher.pushUnread(88L);

    verify(redisTemplate).convertAndSend(eq(NotificationPusher.CHANNEL), eq("88:3"));
    verify(registry, never()).sendUnread(any(Long.class), anyLong());
  }

  @Test
  void pushUnread_treatsNullCountAsZero() {
    when(notificationMapper.selectCount(any())).thenReturn(null);

    pusher.pushUnread(5L);

    verify(redisTemplate).convertAndSend(eq(NotificationPusher.CHANNEL), eq("5:0"));
  }

  @Test
  void pushUnread_fallsBackToLocalPushWhenRedisFails() {
    when(notificationMapper.selectCount(any())).thenReturn(2L);
    doThrow(new RuntimeException("redis down"))
        .when(redisTemplate)
        .convertAndSend(any(String.class), any(String.class));

    pusher.pushUnread(9L);

    verify(registry).sendUnread(9L, 2L);
  }
}
