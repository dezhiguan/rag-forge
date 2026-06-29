package com.ragforge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 组织邀请：按手机号解析（网关）→ PENDING + 站内通知 → 接受写 org_members。 */
@Service
@RequiredArgsConstructor
public class InvitationService {

  private static final int EXPIRE_DAYS = 7;

  private final OrgInvitationMapper invitationMapper;
  private final NotificationMapper notificationMapper;
  private final OrgMemberMapper orgMemberMapper;
  private final OrganizationMapper organizationMapper;
  private final AuthGatewayProxyClient gatewayClient;

  private Long currentUserId() {
    RagAuthContext ctx = RagAuthContextHolder.get();
    if (ctx == null || ctx.userId() == null) {
      throw new BizException(401, "UNAUTHORIZED");
    }
    return ctx.userId();
  }

  /** 发起邀请（仅 OWNER/ADMIN）。按手机号解析：已注册绑 user + 站内通知；未注册暂存待短信拉新。 */
  @Transactional
  public Map<String, Object> invite(Long orgId, String phone, String role) {
    Long uid = currentUserId();
    if (!orgMemberMapper.isOrgAdmin(orgId, uid)) {
      throw new BizException(403, "NOT_ORG_ADMIN");
    }
    Organization org = organizationMapper.selectById(orgId);
    if (org == null) {
      throw new BizException(404, "ORG_NOT_FOUND");
    }
    if ("INDIVIDUAL".equals(org.getType())) {
      // 个人组织不可邀请成员；需协作请先升级为团队组织。
      throw new BizException(409, "INDIVIDUAL_ORG_NO_INVITE");
    }
    String normalizedRole = "ADMIN".equalsIgnoreCase(role) ? "ADMIN" : "MEMBER";

    Map<String, Object> resolved = gatewayClient.resolveByPhone(phone);
    boolean registered = Boolean.TRUE.equals(resolved.get("registered"));
    Long inviteeUserId =
        resolved.get("authUserId") instanceof Number n ? n.longValue() : null;
    String maskedPhone = (String) resolved.get("maskedPhone");

    if (registered && inviteeUserId != null) {
      if (orgMemberMapper.isMember(orgId, inviteeUserId)) {
        throw new BizException(409, "ALREADY_MEMBER");
      }
      Long existing =
          invitationMapper.selectCount(
              new LambdaQueryWrapper<OrgInvitation>()
                  .eq(OrgInvitation::getOrgId, orgId)
                  .eq(OrgInvitation::getInviteeUserId, inviteeUserId)
                  .eq(OrgInvitation::getStatus, "PENDING"));
      if (existing != null && existing > 0) {
        throw new BizException(409, "INVITE_ALREADY_PENDING");
      }
    }

    OrgInvitation inv = new OrgInvitation();
    inv.setOrgId(orgId);
    inv.setInviterUserId(uid);
    inv.setInviteeUserId(inviteeUserId);
    inv.setInviteePhone(maskedPhone);
    inv.setRole(normalizedRole);
    inv.setStatus("PENDING");
    inv.setToken(UUID.randomUUID().toString().replace("-", ""));
    LocalDateTime now = LocalDateTime.now();
    inv.setExpiresAt(now.plusDays(EXPIRE_DAYS));
    inv.setCreatedAt(now);
    inv.setUpdatedAt(now);
    invitationMapper.insert(inv);

    if (registered && inviteeUserId != null) {
      Notification n = new Notification();
      n.setUserId(inviteeUserId);
      n.setType("ORG_INVITE");
      n.setTitle(String.format("「%s」邀请你加入组织", org.getName()));
      n.setBody(String.format("角色 %s · 7 天内有效", normalizedRole));
      n.setRefId(inv.getId());
      n.setCreatedAt(now);
      notificationMapper.insert(n);
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("invitationId", inv.getId());
    out.put("registered", registered);
    out.put("status", "PENDING");
    out.put("maskedPhone", maskedPhone);
    return out;
  }

  /** 我的待处理邀请（被邀请方视角）。 */
  public List<Map<String, Object>> myPending() {
    Long uid = currentUserId();
    List<OrgInvitation> list =
        invitationMapper.selectList(
            new LambdaQueryWrapper<OrgInvitation>()
                .eq(OrgInvitation::getInviteeUserId, uid)
                .eq(OrgInvitation::getStatus, "PENDING")
                .orderByDesc(OrgInvitation::getCreatedAt));
    return list.stream().map(this::toView).toList();
  }

  /** 组织内待处理邀请（管理者视角）。 */
  public List<Map<String, Object>> orgPending(Long orgId) {
    Long uid = currentUserId();
    if (!orgMemberMapper.isOrgAdmin(orgId, uid)) {
      throw new BizException(403, "NOT_ORG_ADMIN");
    }
    List<OrgInvitation> list =
        invitationMapper.selectList(
            new LambdaQueryWrapper<OrgInvitation>()
                .eq(OrgInvitation::getOrgId, orgId)
                .eq(OrgInvitation::getStatus, "PENDING")
                .orderByDesc(OrgInvitation::getCreatedAt));
    return list.stream().map(this::toView).toList();
  }

  /** 接受邀请：写 org_members（已是成员则幂等），邀请置 ACCEPTED，关联通知标已读。 */
  @Transactional
  public void accept(Long invitationId) {
    Long uid = currentUserId();
    OrgInvitation inv = requirePendingForMe(invitationId, uid);
    if (!orgMemberMapper.isMember(inv.getOrgId(), uid)) {
      OrgMember m = new OrgMember();
      m.setOrgId(inv.getOrgId());
      m.setUserId(uid);
      m.setRole(inv.getRole());
      m.setCreatedAt(LocalDateTime.now());
      orgMemberMapper.insert(m);
    }
    inv.setStatus("ACCEPTED");
    inv.setUpdatedAt(LocalDateTime.now());
    invitationMapper.updateById(inv);
    markInviteNotificationsRead(invitationId, uid);
  }

  /** 拒绝邀请。 */
  @Transactional
  public void decline(Long invitationId) {
    Long uid = currentUserId();
    OrgInvitation inv = requirePendingForMe(invitationId, uid);
    inv.setStatus("DECLINED");
    inv.setUpdatedAt(LocalDateTime.now());
    invitationMapper.updateById(inv);
    markInviteNotificationsRead(invitationId, uid);
  }

  /** 撤销邀请（发起组织的管理者）。 */
  @Transactional
  public void revoke(Long orgId, Long invitationId) {
    Long uid = currentUserId();
    if (!orgMemberMapper.isOrgAdmin(orgId, uid)) {
      throw new BizException(403, "NOT_ORG_ADMIN");
    }
    OrgInvitation inv = invitationMapper.selectById(invitationId);
    if (inv == null || !inv.getOrgId().equals(orgId)) {
      throw new BizException(404, "INVITE_NOT_FOUND");
    }
    if (!"PENDING".equals(inv.getStatus())) {
      return;
    }
    inv.setStatus("REVOKED");
    inv.setUpdatedAt(LocalDateTime.now());
    invitationMapper.updateById(inv);
  }

  private OrgInvitation requirePendingForMe(Long invitationId, Long uid) {
    OrgInvitation inv = invitationMapper.selectById(invitationId);
    if (inv == null || !uid.equals(inv.getInviteeUserId())) {
      throw new BizException(404, "INVITE_NOT_FOUND");
    }
    if (!"PENDING".equals(inv.getStatus())) {
      throw new BizException(409, "INVITE_NOT_PENDING");
    }
    if (inv.getExpiresAt() != null && inv.getExpiresAt().isBefore(LocalDateTime.now())) {
      inv.setStatus("EXPIRED");
      inv.setUpdatedAt(LocalDateTime.now());
      invitationMapper.updateById(inv);
      throw new BizException(409, "INVITE_EXPIRED");
    }
    return inv;
  }

  private void markInviteNotificationsRead(Long invitationId, Long uid) {
    Notification patch = new Notification();
    patch.setReadAt(LocalDateTime.now());
    notificationMapper.update(
        patch,
        new LambdaQueryWrapper<Notification>()
            .eq(Notification::getUserId, uid)
            .eq(Notification::getType, "ORG_INVITE")
            .eq(Notification::getRefId, invitationId)
            .isNull(Notification::getReadAt));
  }

  private Map<String, Object> toView(OrgInvitation inv) {
    Organization org = organizationMapper.selectById(inv.getOrgId());
    Map<String, Object> v = new LinkedHashMap<>();
    v.put("id", inv.getId());
    v.put("orgId", inv.getOrgId());
    v.put("orgName", org == null ? null : org.getName());
    v.put("role", inv.getRole());
    v.put("status", inv.getStatus());
    v.put("maskedPhone", inv.getInviteePhone());
    v.put("createdAt", inv.getCreatedAt());
    return v;
  }
}
