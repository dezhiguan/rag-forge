package com.ragforge.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.mapper.NotificationMapper;
import com.ragforge.model.entity.Notification;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 未读数推送器：计算目标用户最新未读数，经 Redis Pub/Sub 广播到持有该用户 SSE 连接的副本 （api 3 副本，连接可能落在任意副本）。Redis 不可用时降级为仅推本地连接，保证同副本可达。
 */
@Component
@RequiredArgsConstructor
public class NotificationPusher {

  private static final Logger log = LoggerFactory.getLogger(NotificationPusher.class);

  /** 跨副本广播频道；payload 形如 {userId}:{count}。 */
  static final String CHANNEL = "ragforge:notif:unread";

  private final NotificationMapper notificationMapper;
  private final StringRedisTemplate redisTemplate;
  private final SseEmitterRegistry registry;

  /** 推送某用户的最新未读数。必须在通知落库（事务提交）之后调用，避免推出脏未读数。 */
  public void pushUnread(Long userId) {
    long count = unreadCount(userId);
    String payload = userId + ":" + count;
    try {
      redisTemplate.convertAndSend(CHANNEL, payload);
    } catch (Exception ex) {
      // Redis 广播失败降级：直接推本地连接，不影响主流程
      log.warn("notif redis publish failed, fallback to local push: {}", ex.getMessage());
      registry.sendUnread(userId, count);
    }
  }

  private long unreadCount(Long userId) {
    Long c =
        notificationMapper.selectCount(
            new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .isNull(Notification::getReadAt));
    return c == null ? 0L : c;
  }
}
