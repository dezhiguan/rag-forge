package com.ragforge.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

class NotificationRedisConfigTest {

  @Test
  void notificationListenerContainer_isWiredToConnectionFactory() {
    RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
    NotificationPushListener listener = new NotificationPushListener(new SseEmitterRegistry());

    RedisMessageListenerContainer container =
        new NotificationRedisConfig().notificationListenerContainer(connectionFactory, listener);

    assertThat(container).isNotNull();
    assertThat(container.getConnectionFactory()).isSameAs(connectionFactory);
  }
}
