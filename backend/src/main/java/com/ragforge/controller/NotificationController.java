package com.ragforge.controller;

import com.ragforge.common.Result;
import com.ragforge.model.entity.Notification;
import com.ragforge.service.NotificationService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 站内通知中心。 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

  private final NotificationService notificationService;

  @GetMapping
  public Result<List<Notification>> list(
      @RequestParam(name = "unreadOnly", defaultValue = "false") boolean unreadOnly) {
    return Result.ok(notificationService.listMine(unreadOnly));
  }

  @GetMapping("/unread-count")
  public Result<Map<String, Object>> unreadCount() {
    return Result.ok(Map.of("count", notificationService.unreadCount()));
  }

  @PostMapping("/{id}/read")
  public Result<Void> markRead(@PathVariable Long id) {
    notificationService.markRead(id);
    return Result.ok();
  }

  @PostMapping("/read-all")
  public Result<Void> markAllRead() {
    notificationService.markAllRead();
    return Result.ok();
  }
}
