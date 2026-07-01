package com.ragforge.notification;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** 装配站内通知未读数广播的 Redis Pub/Sub 监听容器。 */
@Configuration
public class NotificationRedisConfig {

  @Bean
  public RedisMessageListenerContainer notificationListenerContainer(
      RedisConnectionFactory connectionFactory, NotificationPushListener listener) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.addMessageListener(listener, new ChannelTopic(NotificationPusher.CHANNEL));
    return container;
  }
}
