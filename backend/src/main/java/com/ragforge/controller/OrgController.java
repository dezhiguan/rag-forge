package com.ragforge.controller;

import com.ragforge.common.Result;
import com.ragforge.service.InvitationService;
import com.ragforge.service.OrgService;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 组织与成员管理 API（GitHub 式个人/组织权限）。 */
@RestController
@RequestMapping("/api/v1/orgs")
@RequiredArgsConstructor
public class OrgController {

  private final OrgService orgService;
  private final InvitationService invitationService;

  @PostMapping
  public Result<Map<String, Object>> create(@RequestBody OrgCreateRequest req) {
    return Result.ok(orgService.createOrganization(req.getSlug(), req.getName()));
  }

  @GetMapping
  public Result<List<Map<String, Object>>> listMine() {
    return Result.ok(orgService.listMyOrganizations());
  }

  @GetMapping("/{orgId}")
  public Result<Map<String, Object>> detail(@PathVariable Long orgId) {
    return Result.ok(orgService.getOrganization(orgId));
  }

  /** 更新组织名称/slug（仅 OWNER/ADMIN）。 */
  @PatchMapping("/{orgId}")
  public Result<Map<String, Object>> update(
      @PathVariable Long orgId, @RequestBody OrgUpdateRequest req) {
    return Result.ok(orgService.updateOrganization(orgId, req.getName(), req.getSlug()));
  }

  /** 转移所有权（仅 OWNER）。 */
  @PostMapping("/{orgId}/transfer-owner")
  public Result<Void> transferOwner(
      @PathVariable Long orgId, @RequestBody MemberRequest req) {
    orgService.transferOwnership(orgId, req.getUserId());
    return Result.ok();
  }

  /** 删除组织（仅 OWNER，须先清空组织知识库）。 */
  @DeleteMapping("/{orgId}")
  public Result<Void> deleteOrg(@PathVariable Long orgId) {
    orgService.deleteOrganization(orgId);
    return Result.ok();
  }

  /** 退出组织（成员退出自己；唯一 OWNER 需先转移所有权）。 */
  @PostMapping("/{orgId}/leave")
  public Result<Void> leave(@PathVariable Long orgId) {
    orgService.leaveOrganization(orgId);
    return Result.ok();
  }

  @GetMapping("/{orgId}/members")
  public Result<List<Map<String, Object>>> members(@PathVariable Long orgId) {
    return Result.ok(orgService.listMembers(orgId));
  }

  /** 成员候选搜索（仅 OWNER/ADMIN）：按用户名/邮箱/显示名匹配,供添加成员时选人。 */
  @GetMapping("/{orgId}/member-candidates")
  public Result<List<Map<String, Object>>> memberCandidates(
      @PathVariable Long orgId, @RequestParam("q") String q) {
    return Result.ok(orgService.searchMemberCandidates(orgId, q));
  }

  @PostMapping("/{orgId}/members")
  public Result<Void> addMember(
      @PathVariable Long orgId, @RequestBody MemberRequest req) {
    orgService.addMember(orgId, req.getUserId(), req.getRole());
    return Result.ok();
  }

  @PatchMapping("/{orgId}/members/{userId}")
  public Result<Void> updateMember(
      @PathVariable Long orgId, @PathVariable Long userId, @RequestBody MemberRequest req) {
    orgService.updateMemberRole(orgId, userId, req.getRole());
    return Result.ok();
  }

  @DeleteMapping("/{orgId}/members/{userId}")
  public Result<Void> removeMember(@PathVariable Long orgId, @PathVariable Long userId) {
    orgService.removeMember(orgId, userId);
    return Result.ok();
  }

  /** 按手机号邀请成员（仅 OWNER/ADMIN）。 */
  @PostMapping("/{orgId}/invitations")
  public Result<Map<String, Object>> invite(
      @PathVariable Long orgId, @RequestBody InviteRequest req) {
    return Result.ok(invitationService.invite(orgId, req.getPhone(), req.getRole()));
  }

  /** 组织内待处理邀请（仅 OWNER/ADMIN）。 */
  @GetMapping("/{orgId}/invitations")
  public Result<List<Map<String, Object>>> orgInvitations(@PathVariable Long orgId) {
    return Result.ok(invitationService.orgPending(orgId));
  }

  /** 撤销邀请（仅 OWNER/ADMIN）。 */
  @DeleteMapping("/{orgId}/invitations/{invitationId}")
  public Result<Void> revokeInvitation(
      @PathVariable Long orgId, @PathVariable Long invitationId) {
    invitationService.revoke(orgId, invitationId);
    return Result.ok();
  }

  @Data
  public static class InviteRequest {
    private String phone;
    private String role;
  }

  @Data
  public static class OrgCreateRequest {
    private String slug;
    private String name;
  }

  @Data
  public static class OrgUpdateRequest {
    private String name;
    private String slug;
  }

  @Data
  public static class MemberRequest {
    private Long userId;
    private String role;
  }
}
