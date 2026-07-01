package com.ragforge.notification;

import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Redis Pub/Sub 监听器：收到 {@link NotificationPusher#CHANNEL} 上的 {userId}:{count} 消息后， 向本副本持有的该用户 SSE
 * 连接下发未读数。所有副本（含发布方）都会收到并各自下发本地连接。
 */
@Component
@RequiredArgsConstructor
public class NotificationPushListener implements MessageListener {

  private static final Logger log = LoggerFactory.getLogger(NotificationPushListener.class);

  private final SseEmitterRegistry registry;

  @Override
  public void onMessage(Message message, byte[] pattern) {
    String body = new String(message.getBody(), StandardCharsets.UTF_8);
    int idx = body.lastIndexOf(':');
    if (idx <= 0 || idx == body.length() - 1) {
      return;
    }
    try {
      Long userId = Long.valueOf(body.substring(0, idx));
      long count = Long.parseLong(body.substring(idx + 1));
      registry.sendUnread(userId, count);
    } catch (NumberFormatException ex) {
      log.warn("notif push listener got malformed payload: {}", body);
    }
  }
}
