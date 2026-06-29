package com.ragforge.controller;

import com.ragforge.common.Result;
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

  @Data
  public static class OrgCreateRequest {
    private String slug;
    private String name;
  }

  @Data
  public static class MemberRequest {
    private Long userId;
    private String role;
  }
}
