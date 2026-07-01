package com.ragforge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.auth.AuthGatewayProxyClient;
import com.ragforge.common.BizException;
import com.ragforge.mapper.NotificationMapper;
import com.ragforge.mapper.OrgInvitationMapper;
import com.ragforge.mapper.OrgMemberMapper;
import com.ragforge.mapper.OrganizationMapper;
import com.ragforge.model.entity.Notification;
import com.ragforge.model.entity.OrgInvitation;
import com.ragforge.model.entity.OrgMember;
import com.ragforge.model.entity.Organization;
import com.ragforge.notification.NotificationPusher;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InvitationServiceTest {

  @Mock private OrgInvitationMapper invitationMapper;
  @Mock private NotificationMapper notificationMapper;
  @Mock private OrgMemberMapper orgMemberMapper;
  @Mock private OrganizationMapper organizationMapper;
  @Mock private AuthGatewayProxyClient gatewayClient;
  @Mock private NotificationPusher notificationPusher;

  @InjectMocks private InvitationService invitationService;

  @BeforeEach
  void setAuth() {
    RagAuthContextHolder.set(new RagAuthContext(7L, "USER", Set.of(16L), Set.of(16L), Set.of(), "USER", "7"));
  }

  @AfterEach
  void clearAuth() {
    RagAuthContextHolder.clear();
  }

  @Test
  void invite_registeredUser_createsInvitationAndNotification() {
    Organization org = organization(16L, "TEAM");
    when(orgMemberMapper.isOrgAdmin(16L, 7L)).thenReturn(true);
    when(organizationMapper.selectById(16L)).thenReturn(org);
    when(gatewayClient.resolveByPhone("13800000000"))
        .thenReturn(Map.of("registered", true, "authUserId", 88L, "maskedPhone", "138****0000"));
    when(orgMemberMapper.isMember(16L, 88L)).thenReturn(false);
    when(invitationMapper.selectCount(any())).thenReturn(0L);

    Map<String, Object> result = invitationService.invite(16L, "13800000000", "ADMIN");

    assertThat(result)
        .containsEntry("registered", true)
        .containsEntry("status", "PENDING")
        .containsEntry("maskedPhone", "138****0000");
    ArgumentCaptor<OrgInvitation> invitation = ArgumentCaptor.forClass(OrgInvitation.class);
    verify(invitationMapper).insert(invitation.capture());
    assertThat(invitation.getValue().getOrgId()).isEqualTo(16L);
    assertThat(invitation.getValue().getInviteeUserId()).isEqualTo(88L);
    assertThat(invitation.getValue().getRole()).isEqualTo("ADMIN");
    assertThat(invitation.getValue().getToken()).hasSize(32);
    ArgumentCaptor<Notification> notification = ArgumentCaptor.forClass(Notification.class);
    verify(notificationMapper).insert(notification.capture());
    assertThat(notification.getValue().getUserId()).isEqualTo(88L);
    assertThat(notification.getValue().getType()).isEqualTo("ORG_INVITE");
  }

  @Test
  void invite_unregisteredUser_skipsNotification() {
    when(orgMemberMapper.isOrgAdmin(16L, 7L)).thenReturn(true);
    when(organizationMapper.selectById(16L)).thenReturn(organization(16L, "TEAM"));
    when(gatewayClient.resolveByPhone("13800000000"))
        .thenReturn(Map.of("registered", false, "maskedPhone", "138****0000"));

    Map<String, Object> result = invitationService.invite(16L, "13800000000", "member");

    assertThat(result).containsEntry("registered", false);
    verify(invitationMapper).insert(any(OrgInvitation.class));
    verify(notificationMapper, never()).insert(any(Notification.class));
  }

  @Test
  void invite_individualOrg_throws409() {
    when(orgMemberMapper.isOrgAdmin(16L, 7L)).thenReturn(true);
    when(organizationMapper.selectById(16L)).thenReturn(organization(16L, "INDIVIDUAL"));

    assertThatThrownBy(() -> invitationService.invite(16L, "13800000000", "MEMBER"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(409));
  }

  @Test
  void myPending_mapsInvitationsToViews() {
    OrgInvitation invitation = invitation(30L, 16L, 7L, "MEMBER", "PENDING");
    when(invitationMapper.selectList(any())).thenReturn(List.of(invitation));
    when(organizationMapper.selectById(16L)).thenReturn(organization(16L, "TEAM"));

    List<Map<String, Object>> result = invitationService.myPending();

    assertThat(result).hasSize(1);
    assertThat(result.getFirst())
        .containsEntry("id", 30L)
        .containsEntry("orgId", 16L)
        .containsEntry("orgName", "Acme")
        .containsEntry("role", "MEMBER");
  }

  @Test
  void accept_pendingInvitation_insertsMemberAndMarksNotificationRead() {
    OrgInvitation invitation = invitation(30L, 16L, 7L, "MEMBER", "PENDING");
    when(invitationMapper.selectById(30L)).thenReturn(invitation);
    when(orgMemberMapper.isMember(16L, 7L)).thenReturn(false);

    invitationService.accept(30L);

    verify(orgMemberMapper).insert(any(OrgMember.class));
    assertThat(invitation.getStatus()).isEqualTo("ACCEPTED");
    verify(invitationMapper).updateById(invitation);
    verify(notificationMapper).update(any(Notification.class), any());
  }

  @Test
  void accept_existingMember_isIdempotentForMemberInsert() {
    OrgInvitation invitation = invitation(30L, 16L, 7L, "ADMIN", "PENDING");
    when(invitationMapper.selectById(30L)).thenReturn(invitation);
    when(orgMemberMapper.isMember(16L, 7L)).thenReturn(true);

    invitationService.accept(30L);

    verify(orgMemberMapper, never()).insert(any(OrgMember.class));
    assertThat(invitation.getStatus()).isEqualTo("ACCEPTED");
  }

  @Test
  void decline_pendingInvitation_updatesStatusAndNotification() {
    OrgInvitation invitation = invitation(31L, 16L, 7L, "MEMBER", "PENDING");
    when(invitationMapper.selectById(31L)).thenReturn(invitation);

    invitationService.decline(31L);

    assertThat(invitation.getStatus()).isEqualTo("DECLINED");
    verify(invitationMapper).updateById(invitation);
    verify(notificationMapper).update(any(Notification.class), any());
  }

  @Test
  void accept_expiredInvitation_marksExpiredAndThrows409() {
    OrgInvitation invitation = invitation(32L, 16L, 7L, "MEMBER", "PENDING");
    invitation.setExpiresAt(LocalDateTime.now().minusDays(1));
    when(invitationMapper.selectById(32L)).thenReturn(invitation);

    assertThatThrownBy(() -> invitationService.accept(32L))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(409));
    assertThat(invitation.getStatus()).isEqualTo("EXPIRED");
    verify(invitationMapper).updateById(invitation);
  }

  @Test
  void revoke_pendingInvitation_updatesStatus() {
    OrgInvitation invitation = invitation(33L, 16L, 88L, "MEMBER", "PENDING");
    when(orgMemberMapper.isOrgAdmin(16L, 7L)).thenReturn(true);
    when(invitationMapper.selectById(33L)).thenReturn(invitation);

    invitationService.revoke(16L, 33L);

    assertThat(invitation.getStatus()).isEqualTo("REVOKED");
    verify(invitationMapper).updateById(invitation);
  }

  @Test
  void revoke_nonPendingInvitation_doesNothing() {
    OrgInvitation invitation = invitation(34L, 16L, 88L, "MEMBER", "ACCEPTED");
    when(orgMemberMapper.isOrgAdmin(16L, 7L)).thenReturn(true);
    when(invitationMapper.selectById(34L)).thenReturn(invitation);

    invitationService.revoke(16L, 34L);

    verify(invitationMapper, never()).updateById(invitation);
  }

  @Test
  void accept_withInviter_insertsAcceptedReceiptAndPushes() {
    OrgInvitation invitation = invitation(40L, 16L, 7L, "MEMBER", "PENDING");
    invitation.setInviterUserId(88L);
    when(invitationMapper.selectById(40L)).thenReturn(invitation);
    when(orgMemberMapper.isMember(16L, 7L)).thenReturn(false);
    when(notificationMapper.selectCount(any())).thenReturn(0L);
    when(organizationMapper.selectById(16L)).thenReturn(organization(16L, "TEAM"));

    invitationService.accept(40L);

    ArgumentCaptor<Notification> receipt = ArgumentCaptor.forClass(Notification.class);
    verify(notificationMapper).insert(receipt.capture());
    assertThat(receipt.getValue().getUserId()).isEqualTo(88L);
    assertThat(receipt.getValue().getType()).isEqualTo("ORG_INVITE_ACCEPTED");
    assertThat(receipt.getValue().getRefId()).isEqualTo(40L);
    assertThat(receipt.getValue().getBody()).contains("Acme");
    verify(notificationPusher).pushUnread(88L);
  }

  @Test
  void accept_withInviter_skipsReceiptWhenAlreadyExists() {
    OrgInvitation invitation = invitation(41L, 16L, 7L, "MEMBER", "PENDING");
    invitation.setInviterUserId(88L);
    when(invitationMapper.selectById(41L)).thenReturn(invitation);
    when(orgMemberMapper.isMember(16L, 7L)).thenReturn(false);
    when(notificationMapper.selectCount(any())).thenReturn(1L);

    invitationService.accept(41L);

    verify(notificationMapper, never()).insert(any(Notification.class));
    verify(notificationPusher, never()).pushUnread(any(Long.class));
  }

  @Test
  void accept_withoutInviter_skipsReceipt() {
    OrgInvitation invitation = invitation(42L, 16L, 7L, "MEMBER", "PENDING");
    when(invitationMapper.selectById(42L)).thenReturn(invitation);
    when(orgMemberMapper.isMember(16L, 7L)).thenReturn(false);

    invitationService.accept(42L);

    verify(notificationMapper, never()).insert(any(Notification.class));
    verify(notificationPusher, never()).pushUnread(any(Long.class));
  }

  @Test
  void decline_withInviter_insertsDeclinedReceipt() {
    OrgInvitation invitation = invitation(43L, 16L, 7L, "MEMBER", "PENDING");
    invitation.setInviterUserId(88L);
    when(invitationMapper.selectById(43L)).thenReturn(invitation);
    when(notificationMapper.selectCount(any())).thenReturn(0L);
    when(organizationMapper.selectById(16L)).thenReturn(organization(16L, "TEAM"));

    invitationService.decline(43L);

    ArgumentCaptor<Notification> receipt = ArgumentCaptor.forClass(Notification.class);
    verify(notificationMapper).insert(receipt.capture());
    assertThat(receipt.getValue().getUserId()).isEqualTo(88L);
    assertThat(receipt.getValue().getType()).isEqualTo("ORG_INVITE_DECLINED");
    assertThat(receipt.getValue().getRefId()).isEqualTo(43L);
    verify(notificationPusher).pushUnread(88L);
  }

  @Test
  void accept_withActiveTransaction_pushesOnlyAfterCommit() {
    OrgInvitation invitation = invitation(45L, 16L, 7L, "MEMBER", "PENDING");
    invitation.setInviterUserId(88L);
    when(invitationMapper.selectById(45L)).thenReturn(invitation);
    when(orgMemberMapper.isMember(16L, 7L)).thenReturn(false);
    when(notificationMapper.selectCount(any())).thenReturn(0L);
    when(organizationMapper.selectById(16L)).thenReturn(organization(16L, "TEAM"));

    TransactionSynchronizationManager.initSynchronization();
    try {
      invitationService.accept(45L);

      // 事务提交前不得推送（防脏未读数）
      verify(notificationPusher, never()).pushUnread(any(Long.class));

      // 模拟事务提交
      for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
        s.afterCommit();
      }
      verify(notificationPusher).pushUnread(88L);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void accept_withInviter_usesFallbackOrgNameWhenOrgMissing() {
    OrgInvitation invitation = invitation(44L, 16L, 7L, "MEMBER", "PENDING");
    invitation.setInviterUserId(88L);
    when(invitationMapper.selectById(44L)).thenReturn(invitation);
    when(orgMemberMapper.isMember(16L, 7L)).thenReturn(false);
    when(notificationMapper.selectCount(any())).thenReturn(0L);
    when(organizationMapper.selectById(16L)).thenReturn(null);

    invitationService.accept(44L);

    ArgumentCaptor<Notification> receipt = ArgumentCaptor.forClass(Notification.class);
    verify(notificationMapper).insert(receipt.capture());
    assertThat(receipt.getValue().getBody()).contains("组织");
  }

  private static Organization organization(Long id, String type) {
    Organization org = new Organization();
    org.setId(id);
    org.setSlug("acme");
    org.setName("Acme");
    org.setType(type);
    return org;
  }

  private static OrgInvitation invitation(
      Long id, Long orgId, Long inviteeUserId, String role, String status) {
    OrgInvitation invitation = new OrgInvitation();
    invitation.setId(id);
    invitation.setOrgId(orgId);
    invitation.setInviteeUserId(inviteeUserId);
    invitation.setInviteePhone("138****0000");
    invitation.setRole(role);
    invitation.setStatus(status);
    invitation.setExpiresAt(LocalDateTime.now().plusDays(1));
    invitation.setCreatedAt(LocalDateTime.now().minusHours(1));
    return invitation;
  }
}
