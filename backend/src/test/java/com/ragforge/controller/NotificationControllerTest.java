package com.ragforge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.common.Result;
import com.ragforge.model.entity.Notification;
import com.ragforge.notification.NotificationSseService;
import com.ragforge.service.NotificationService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

  @Mock private NotificationService notificationService;
  @Mock private NotificationSseService notificationSseService;

  @InjectMocks private NotificationController controller;

  @Test
  void list_delegatesToServiceWithUnreadFlag() {
    Notification n = new Notification();
    when(notificationService.listMine(true)).thenReturn(List.of(n));

    Result<List<Notification>> result = controller.list(true);

    assertThat(result.getData()).containsExactly(n);
  }

  @Test
  void unreadCount_wrapsCountInPayload() {
    when(notificationService.unreadCount()).thenReturn(4L);

    Result<Map<String, Object>> result = controller.unreadCount();

    assertThat(result.getData()).containsEntry("count", 4L);
  }

  @Test
  void markRead_delegatesToService() {
    controller.markRead(11L);

    verify(notificationService).markRead(11L);
  }

  @Test
  void markAllRead_delegatesToService() {
    controller.markAllRead();

    verify(notificationService).markAllRead();
  }

  @Test
  void stream_delegatesToSseService() {
    SseEmitter emitter = new SseEmitter();
    when(notificationSseService.subscribe("tok")).thenReturn(emitter);

    SseEmitter result = controller.stream("tok");

    assertThat(result).isSameAs(emitter);
  }
}
