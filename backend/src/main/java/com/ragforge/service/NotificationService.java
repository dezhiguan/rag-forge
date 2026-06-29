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
