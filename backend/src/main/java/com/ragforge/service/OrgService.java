package com.ragforge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.auth.UserProfileService;
import com.ragforge.common.BizException;
import com.ragforge.mapper.OrgMemberMapper;
import com.ragforge.mapper.OrganizationMapper;
import com.ragforge.mapper.UserProfileMapper;
import com.ragforge.model.entity.OrgMember;
import com.ragforge.model.entity.Organization;
import com.ragforge.model.entity.UserProfile;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 组织与成员管理：GitHub 式个人/组织权限的本地实现。 */
@Service
@RequiredArgsConstructor
public class OrgService {

  private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$");
  private static final String OWNER = "OWNER";
  private static final String ADMIN = "ADMIN";
  private static final String MEMBER = "MEMBER";

  private final OrganizationMapper organizationMapper;
  private final OrgMemberMapper orgMemberMapper;
  private final UserProfileMapper userProfileMapper;
  private final UserProfileService userProfileService;
  private final com.ragforge.mapper.KnowledgeBaseMapper knowledgeBaseMapper;

  private Long currentUserId() {
    RagAuthContext ctx = RagAuthContextHolder.get();
    if (ctx == null || ctx.userId() == null) {
      throw new BizException(401, "UNAUTHORIZED");
    }
    return ctx.userId();
  }

  /** 创建组织，创建者成为 OWNER。 */
  @Transactional
  public Map<String, Object> createOrganization(String slug, String name) {
    // 按原样校验（不预先 toLowerCase）：slug 必须本身就是小写 GitHub 式标识，
    // 大写如 'UPPER' 应直接判非法，而不是被悄悄转小写后建库。
    String normalizedSlug = slug == null ? "" : slug.trim();
    if (!SLUG.matcher(normalizedSlug).matches()) {
      throw new BizException(400, "ORG_SLUG_INVALID");
    }
    if (!StringUtils.hasText(name)) {
      throw new BizException(400, "ORG_NAME_REQUIRED");
    }
    Long uid = currentUserId();
    Long exists =
        organizationMapper.selectCount(
            new LambdaQueryWrapper<Organization>().eq(Organization::getSlug, normalizedSlug));
    if (exists != null && exists > 0) {
      throw new BizException(409, "ORG_SLUG_TAKEN");
    }
    Organization org = new Organization();
    org.setSlug(normalizedSlug);
    org.setName(name.trim());
    org.setCreatedByUserId(uid);
    LocalDateTime now = LocalDateTime.now();
    org.setCreatedAt(now);
    org.setUpdatedAt(now);
    organizationMapper.insert(org);

    OrgMember owner = new OrgMember();
    owner.setOrgId(org.getId());
    owner.setUserId(uid);
    owner.setRole(OWNER);
    owner.setCreatedAt(now);
    orgMemberMapper.insert(owner);

    return toView(org, OWNER);
  }

  /** 我所属的组织列表（含我的角色）。 */
  public List<Map<String, Object>> listMyOrganizations() {
    Long uid = currentUserId();
    List<Long> orgIds = orgMemberMapper.findMemberOrgIds(uid);
    if (orgIds == null || orgIds.isEmpty()) {
      return List.of();
    }
    return organizationMapper
        .selectBatchIds(orgIds)
        .stream()
        .map(o -> toView(o, orgMemberMapper.findRole(o.getId(), uid)))
        .toList();
  }

  /** 组织详情（仅成员可见）。 */
  public Map<String, Object> getOrganization(Long orgId) {
    Long uid = currentUserId();
    requireMember(orgId, uid);
    Organization org = requireOrg(orgId);
    return toView(org, orgMemberMapper.findRole(orgId, uid));
  }

  /** 成员列表（仅成员可见）；回填本地用户资料的显示名/邮箱，避免前端只能显示「用户 {id}」。 */
  public List<Map<String, Object>> listMembers(Long orgId) {
    Long uid = currentUserId();
    requireMember(orgId, uid);
    List<OrgMember> members = orgMemberMapper.listByOrg(orgId);
    Map<Long, UserProfile> profiles = loadProfiles(members.stream().map(OrgMember::getUserId).toList());
    return members.stream()
        .map(
            m -> {
              UserProfile p = profiles.get(m.getUserId());
              Map<String, Object> v = new LinkedHashMap<>();
              v.put("userId", m.getUserId());
              v.put("role", m.getRole());
              v.put("displayName", userProfileService.resolveDisplayName(p, m.getUserId()));
              v.put("email", p == null ? null : p.getEmail());
              v.put("createdAt", m.getCreatedAt());
              return v;
            })
        .toList();
  }

  /** 成员候选搜索（仅 OWNER/ADMIN）：按用户名/邮箱/显示名匹配本地用户，并标注是否已是成员。 */
  public List<Map<String, Object>> searchMemberCandidates(Long orgId, String q) {
    Long uid = currentUserId();
    requireOrgAdmin(orgId, uid);
    return userProfileService.search(q, 10).stream()
        .map(
            p -> {
              Map<String, Object> v = new LinkedHashMap<>();
              v.put("userId", p.getAuthUserId());
              v.put("displayName", userProfileService.resolveDisplayName(p, p.getAuthUserId()));
              v.put("email", p.getEmail());
              v.put("maskedPhone", p.getMaskedPhone());
              v.put("alreadyMember", orgMemberMapper.isMember(orgId, p.getAuthUserId()));
              return v;
            })
        .toList();
  }

  private Map<Long, UserProfile> loadProfiles(List<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return Map.of();
    }
    return userProfileMapper.selectBatchIds(userIds).stream()
        .collect(Collectors.toMap(UserProfile::getAuthUserId, Function.identity()));
  }

  /** 添加成员（仅 OWNER/ADMIN）。仅 OWNER 可授予 OWNER 角色。 */
  @Transactional
  public void addMember(Long orgId, Long targetUserId, String role) {
    Long uid = currentUserId();
    requireOrgAdmin(orgId, uid);
    String normalizedRole = normalizeRole(role);
    if (targetUserId == null) {
      throw new BizException(400, "USER_ID_REQUIRED");
    }
    if (OWNER.equals(normalizedRole) && !OWNER.equals(orgMemberMapper.findRole(orgId, uid))) {
      throw new BizException(403, "ONLY_OWNER_CAN_GRANT_OWNER");
    }
    if (orgMemberMapper.isMember(orgId, targetUserId)) {
      throw new BizException(409, "ALREADY_MEMBER");
    }
    OrgMember member = new OrgMember();
    member.setOrgId(orgId);
    member.setUserId(targetUserId);
    member.setRole(normalizedRole);
    member.setCreatedAt(LocalDateTime.now());
    orgMemberMapper.insert(member);
  }

  /** 调整成员角色（仅 OWNER/ADMIN）。不可降级最后一个 OWNER；仅 OWNER 可授予/撤销 OWNER。 */
  @Transactional
  public void updateMemberRole(Long orgId, Long targetUserId, String role) {
    Long uid = currentUserId();
    requireOrgAdmin(orgId, uid);
    String normalizedRole = normalizeRole(role);
    OrgMember target = findMember(orgId, targetUserId);
    boolean ownerActorRequired =
        OWNER.equals(normalizedRole) || OWNER.equals(target.getRole());
    if (ownerActorRequired && !OWNER.equals(orgMemberMapper.findRole(orgId, uid))) {
      throw new BizException(403, "ONLY_OWNER_CAN_CHANGE_OWNER");
    }
    if (OWNER.equals(target.getRole()) && !OWNER.equals(normalizedRole) && ownerCount(orgId) <= 1) {
      throw new BizException(409, "LAST_OWNER");
    }
    target.setRole(normalizedRole);
    orgMemberMapper.updateById(target);
  }

  /** 移除成员（仅 OWNER/ADMIN）。不可移除最后一个 OWNER。 */
  @Transactional
  public void removeMember(Long orgId, Long targetUserId) {
    Long uid = currentUserId();
    requireOrgAdmin(orgId, uid);
    OrgMember target = findMember(orgId, targetUserId);
    if (OWNER.equals(target.getRole()) && ownerCount(orgId) <= 1) {
      throw new BizException(409, "LAST_OWNER");
    }
    orgMemberMapper.deleteById(target.getId());
  }

  /** 更新组织名称/slug（仅 OWNER/ADMIN）。slug 需合法且全局唯一（排除自身）。 */
  @Transactional
  public Map<String, Object> updateOrganization(Long orgId, String name, String slug) {
    Long uid = currentUserId();
    requireOrgAdmin(orgId, uid);
    Organization org = requireOrg(orgId);
    if (StringUtils.hasText(name)) {
      org.setName(name.trim());
    }
    if (StringUtils.hasText(slug)) {
      String normalizedSlug = slug.trim();
      if (!SLUG.matcher(normalizedSlug).matches()) {
        throw new BizException(400, "ORG_SLUG_INVALID");
      }
      if (!normalizedSlug.equals(org.getSlug())) {
        Long taken =
            organizationMapper.selectCount(
                new LambdaQueryWrapper<Organization>()
                    .eq(Organization::getSlug, normalizedSlug)
                    .ne(Organization::getId, orgId));
        if (taken != null && taken > 0) {
          throw new BizException(409, "ORG_SLUG_TAKEN");
        }
        org.setSlug(normalizedSlug);
      }
    }
    org.setUpdatedAt(LocalDateTime.now());
    organizationMapper.updateById(org);
    return toView(org, orgMemberMapper.findRole(orgId, uid));
  }

  /** 转移所有权（仅 OWNER）：目标须为本组织成员；原 OWNER 降为 ADMIN，目标升为 OWNER。 */
  @Transactional
  public void transferOwnership(Long orgId, Long targetUserId) {
    Long uid = currentUserId();
    requireOwner(orgId, uid);
    if (uid.equals(targetUserId)) {
      throw new BizException(400, "ALREADY_OWNER");
    }
    OrgMember target = findMember(orgId, targetUserId);
    OrgMember me = findMember(orgId, uid);
    target.setRole(OWNER);
    orgMemberMapper.updateById(target);
    me.setRole(ADMIN);
    orgMemberMapper.updateById(me);
  }

  /** 删除组织（仅 OWNER）：须先清空组织名下知识库（避免悬挂归属）。级联删除成员与邀请。 */
  @Transactional
  public void deleteOrganization(Long orgId) {
    Long uid = currentUserId();
    requireOwner(orgId, uid);
    Long kbCount =
        knowledgeBaseMapper.selectCount(
            new LambdaQueryWrapper<com.ragforge.model.entity.KnowledgeBase>()
                .eq(com.ragforge.model.entity.KnowledgeBase::getOrgId, orgId)
                .ne(com.ragforge.model.entity.KnowledgeBase::getStatus, "deleted"));
    if (kbCount != null && kbCount > 0) {
      throw new BizException(409, "ORG_HAS_KBS");
    }
    organizationMapper.deleteById(orgId); // org_members / org_invitations 由外键 ON DELETE CASCADE 清理
  }

  // ---- helpers ----

  private void requireOwner(Long orgId, Long uid) {
    if (!OWNER.equals(orgMemberMapper.findRole(orgId, uid))) {
      throw new BizException(403, "ONLY_OWNER");
    }
  }

  private Organization requireOrg(Long orgId) {
    Organization org = orgId == null ? null : organizationMapper.selectById(orgId);
    if (org == null) {
      throw new BizException(404, "ORG_NOT_FOUND");
    }
    return org;
  }

  private void requireMember(Long orgId, Long uid) {
    if (!orgMemberMapper.isMember(orgId, uid)) {
      throw new BizException(403, "NOT_ORG_MEMBER");
    }
  }

  private void requireOrgAdmin(Long orgId, Long uid) {
    if (!orgMemberMapper.isOrgAdmin(orgId, uid)) {
      throw new BizException(403, "NOT_ORG_ADMIN");
    }
  }

  private OrgMember findMember(Long orgId, Long targetUserId) {
    OrgMember member =
        orgMemberMapper.selectOne(
            new LambdaQueryWrapper<OrgMember>()
                .eq(OrgMember::getOrgId, orgId)
                .eq(OrgMember::getUserId, targetUserId));
    if (member == null) {
      throw new BizException(404, "MEMBER_NOT_FOUND");
    }
    return member;
  }

  private long ownerCount(Long orgId) {
    Long count =
        orgMemberMapper.selectCount(
            new LambdaQueryWrapper<OrgMember>()
                .eq(OrgMember::getOrgId, orgId)
                .eq(OrgMember::getRole, OWNER));
    return count == null ? 0 : count;
  }

  private String normalizeRole(String role) {
    String r = role == null ? "" : role.trim().toUpperCase();
    if (!OWNER.equals(r) && !ADMIN.equals(r) && !MEMBER.equals(r)) {
      throw new BizException(400, "ROLE_INVALID");
    }
    return r;
  }

  private Map<String, Object> toView(Organization org, String myRole) {
    Map<String, Object> v = new LinkedHashMap<>();
    v.put("id", org.getId());
    v.put("slug", org.getSlug());
    v.put("name", org.getName());
    v.put("myRole", myRole);
    v.put("createdAt", org.getCreatedAt());
    return v;
  }
}
