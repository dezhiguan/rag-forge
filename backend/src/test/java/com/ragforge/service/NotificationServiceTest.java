package com.ragforge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.common.BizException;
import com.ragforge.mapper.NotificationMapper;
import com.ragforge.model.entity.Notification;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

  @Mock private NotificationMapper notificationMapper;

  @InjectMocks private NotificationService notificationService;

  @BeforeEach
  void setAuth() {
    RagAuthContextHolder.set(new RagAuthContext(42L, "USER", Set.of(7L), Set.of(), Set.of(), "USER", "42"));
  }

  @AfterEach
  void clearAuth() {
    RagAuthContextHolder.clear();
  }

  @Test
  void listMine_returnsUnreadNotificationsWhenRequested() {
    Notification notification = new Notification();
    notification.setId(9L);
    when(notificationMapper.selectList(any())).thenReturn(List.of(notification));

    List<Notification> result = notificationService.listMine(true);

    assertThat(result).containsExactly(notification);
    verify(notificationMapper).selectList(any());
  }

  @Test
  void unreadCount_returnsZeroWhenMapperReturnsNull() {
    when(notificationMapper.selectCount(any())).thenReturn(null);

    assertThat(notificationService.unreadCount()).isZero();
  }

  @Test
  void markRead_updatesCurrentUsersUnreadNotification() {
    ArgumentCaptor<Notification> patch = ArgumentCaptor.forClass(Notification.class);

    notificationService.markRead(11L);

    verify(notificationMapper).update(patch.capture(), any());
    assertThat(patch.getValue().getReadAt()).isNotNull();
  }

  @Test
  void markAllRead_updatesCurrentUsersUnreadNotifications() {
    ArgumentCaptor<Notification> patch = ArgumentCaptor.forClass(Notification.class);

    notificationService.markAllRead();

    verify(notificationMapper).update(patch.capture(), any());
    assertThat(patch.getValue().getReadAt()).isNotNull();
  }

  @Test
  void unreadCount_withoutAuth_throws401() {
    RagAuthContextHolder.clear();

    assertThatThrownBy(() -> notificationService.unreadCount())
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(401));
  }
}
