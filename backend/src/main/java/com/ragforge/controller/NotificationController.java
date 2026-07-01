package com.ragforge.controller;

import com.ragforge.common.Result;
import com.ragforge.model.entity.Notification;
import com.ragforge.notification.NotificationSseService;
import com.ragforge.service.NotificationService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 站内通知中心。 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

  private final NotificationService notificationService;
  private final NotificationSseService notificationSseService;

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

  /**
   * SSE 实时未读数订阅。浏览器 EventSource 不能设自定义 header，故 token 走 query param。 建连即下发一次当前未读数做对齐，之后由服务端在通知落库后增量推送。
   */
  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@RequestParam(name = "token", required = false) String token) {
    return notificationSseService.subscribe(token);
  }
}
