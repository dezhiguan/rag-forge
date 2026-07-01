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
import java.util.UUID;
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
  /** 显示名长度上限，与 organizations.name VARCHAR(128) 对齐；超长返回 400 而非撞 DB 报 500。 */
  private static final int MAX_NAME_LEN = 128;
  private static final String OWNER = "OWNER";
  private static final String ADMIN = "ADMIN";
  private static final String MEMBER = "MEMBER";

  private final OrganizationMapper organizationMapper;
  private final OrgMemberMapper orgMemberMapper;
  private final UserProfileMapper userProfileMapper;
  private final UserProfileService userProfileService;
  private final com.ragforge.mapper.KnowledgeBaseMapper knowledgeBaseMapper;
  private final com.ragforge.mapper.ApiKeyMapper apiKeyMapper;

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
    if (name.trim().length() > MAX_NAME_LEN) {
      throw new BizException(400, "ORG_NAME_TOO_LONG");
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
    org.setType("TEAM"); // 用户显式创建的都是团队组织
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

  /**
   * 确保当前用户有一个个人组织（INDIVIDUAL）；没有则懒创建并设其为 OWNER。 注册/首次访问时调用，替代旧的
   * org_id=null 个人空间。
   */
  @Transactional
  public Organization ensureIndividualOrg(Long userId) {
    Organization existing =
        organizationMapper.selectOne(
            new LambdaQueryWrapper<Organization>()
                .eq(Organization::getType, "INDIVIDUAL")
                .eq(Organization::getCreatedByUserId, userId)
                .last("LIMIT 1"));
    if (existing != null) {
      return existing;
    }
    LocalDateTime now = LocalDateTime.now();
    Organization org = new Organization();
    org.setSlug("u-" + userId + "-" + UUID.randomUUID().toString().substring(0, 6));
    org.setName("个人组织");
    org.setType("INDIVIDUAL");
    org.setCreatedByUserId(userId);
    org.setCreatedAt(now);
    org.setUpdatedAt(now);
    organizationMapper.insert(org);
    OrgMember owner = new OrgMember();
    owner.setOrgId(org.getId());
    owner.setUserId(userId);
    owner.setRole(OWNER);
    owner.setCreatedAt(now);
    orgMemberMapper.insert(owner);
    return org;
  }

  /** 升级个人组织为团队组织（仅 OWNER，仅 INDIVIDUAL）：type 翻转，org_id 不变、知识库零迁移。 */
  @Transactional
  public void upgradeToTeam(Long orgId) {
    Long uid = currentUserId();
    requireOwner(orgId, uid);
    Organization org = requireOrg(orgId);
    if (!"INDIVIDUAL".equals(org.getType())) {
      throw new BizException(409, "NOT_INDIVIDUAL_ORG");
    }
    org.setType("TEAM");
    org.setUpdatedAt(LocalDateTime.now());
    organizationMapper.updateById(org);
  }

  /** 我所属的组织列表（含我的角色）。 */
  public List<Map<String, Object>> listMyOrganizations() {
    Long uid = currentUserId();
    ensureIndividualOrg(uid); // 保证有个人组织（Claude Code 式：一切皆组织）
    List<Long> orgIds = orgMemberMapper.findMemberOrgIds(uid);
    if (orgIds == null || orgIds.isEmpty()) {
      return List.of();
    }
    return organizationMapper
        .selectBatchIds(orgIds)
        .stream()
        .map(
            o -> {
              Map<String, Object> v = toView(o, orgMemberMapper.findRole(o.getId(), uid));
              v.put("memberCount", countMembers(o.getId()));
              v.put("kbCount", countOrgKbs(o.getId()));
              return v;
            })
        .toList();
  }

  private long countMembers(Long orgId) {
    Long c =
        orgMemberMapper.selectCount(
            new LambdaQueryWrapper<OrgMember>().eq(OrgMember::getOrgId, orgId));
    return c == null ? 0 : c;
  }

  private long countOrgKbs(Long orgId) {
    Long c =
        knowledgeBaseMapper.selectCount(
            new LambdaQueryWrapper<com.ragforge.model.entity.KnowledgeBase>()
                .eq(com.ragforge.model.entity.KnowledgeBase::getOrgId, orgId)
                .ne(com.ragforge.model.entity.KnowledgeBase::getStatus, "deleted"));
    return c == null ? 0 : c;
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
    Organization org = organizationMapper.selectById(orgId);
    if (org != null && "INDIVIDUAL".equals(org.getType())) {
      // 个人组织不可加成员；需协作请先升级为团队组织。
      throw new BizException(409, "INDIVIDUAL_ORG_NO_MEMBER");
    }
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
      if (name.trim().length() > MAX_NAME_LEN) {
        throw new BizException(400, "ORG_NAME_TOO_LONG");
      }
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
    // 级联清理该组织的 API key（无外键约束，显式删除，避免治理视图残留孤儿 key）。
    apiKeyMapper.delete(
        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                com.ragforge.model.entity.ApiKey>()
            .eq(com.ragforge.model.entity.ApiKey::getOrgId, orgId));
    // 软删除的知识库仍以 org_id 外键引用本组织，而该外键无 ON DELETE CASCADE；
    // 活跃库已在上方拦截，此处将剩余（软删）库的 org_id 置空，避免删除组织时外键约束报 500。
    knowledgeBaseMapper.update(
        null,
        new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<
                com.ragforge.model.entity.KnowledgeBase>()
            .eq("org_id", orgId)
            .set("org_id", null));
    organizationMapper.deleteById(orgId); // org_members / org_invitations 由外键 ON DELETE CASCADE 清理
  }

  /** 退出组织（任意成员退出自己）；唯一 OWNER 不能直接退出，需先转移所有权。 */
  @Transactional
  public void leaveOrganization(Long orgId) {
    Long uid = currentUserId();
    OrgMember me = findMember(orgId, uid);
    if (OWNER.equals(me.getRole()) && ownerCount(orgId) <= 1) {
      throw new BizException(409, "LAST_OWNER_CANNOT_LEAVE");
    }
    orgMemberMapper.deleteById(me.getId());
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
    v.put("type", org.getType());
    v.put("personal", "INDIVIDUAL".equals(org.getType()));
    v.put("myRole", myRole);
    v.put("createdAt", org.getCreatedAt());
    return v;
  }
}
