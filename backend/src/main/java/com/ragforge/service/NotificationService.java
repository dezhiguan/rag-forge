package com.ragforge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.common.BizException;
import com.ragforge.mapper.NotificationMapper;
import com.ragforge.model.entity.Notification;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 站内通知：当前用户的列表/未读数/标记已读。 */
@Service
@RequiredArgsConstructor
public class NotificationService {

  private static final int LIST_LIMIT = 50;

  private final NotificationMapper notificationMapper;

  private Long currentUserId() {
    RagAuthContext ctx = RagAuthContextHolder.get();
    if (ctx == null || ctx.userId() == null) {
      throw new BizException(401, "UNAUTHORIZED");
    }
    return ctx.userId();
  }

  public List<Notification> listMine(boolean unreadOnly) {
    Long uid = currentUserId();
    LambdaQueryWrapper<Notification> w =
        new LambdaQueryWrapper<Notification>()
            .eq(Notification::getUserId, uid)
            .orderByDesc(Notification::getCreatedAt)
            .last("LIMIT " + LIST_LIMIT);
    if (unreadOnly) {
      w.isNull(Notification::getReadAt);
    }
    return notificationMapper.selectList(w);
  }

  public long unreadCount() {
    Long uid = currentUserId();
    Long c =
        notificationMapper.selectCount(
            new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, uid)
                .isNull(Notification::getReadAt));
    return c == null ? 0L : c;
  }

  public void markRead(Long id) {
    Long uid = currentUserId();
    // 先校验通知存在且属于当前用户：不存在 / 越权他人通知一律 404（不泄露是否存在），
    // 避免"标记不存在的通知""越权标记他人通知"都静默返回 200 success。
    Long owned =
        notificationMapper.selectCount(
            new LambdaQueryWrapper<Notification>()
                .eq(Notification::getId, id)
                .eq(Notification::getUserId, uid));
    if (owned == null || owned == 0) {
      throw new BizException(404, "NOTIFICATION_NOT_FOUND");
    }
    // 已读则幂等（update 命中 0 行也不报错），仅未读时置 readAt。
    Notification patch = new Notification();
    patch.setReadAt(LocalDateTime.now());
    notificationMapper.update(
        patch,
        new LambdaQueryWrapper<Notification>()
            .eq(Notification::getId, id)
            .eq(Notification::getUserId, uid)
            .isNull(Notification::getReadAt));
  }

  public void markAllRead() {
    Long uid = currentUserId();
    Notification patch = new Notification();
    patch.setReadAt(LocalDateTime.now());
    notificationMapper.update(
        patch,
        new LambdaQueryWrapper<Notification>()
            .eq(Notification::getUserId, uid)
            .isNull(Notification::getReadAt));
  }
}
