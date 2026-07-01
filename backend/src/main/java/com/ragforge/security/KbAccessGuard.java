package com.ragforge.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KbAclMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.mapper.OrgMemberMapper;
import com.ragforge.metrics.RagforgeMetrics;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.KnowledgeBase;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component("kbAccessGuard")
@RequiredArgsConstructor
public class KbAccessGuard {

  private static final String SERVICE_ACCOUNT = "SERVICE_ACCOUNT";
  private static final String SYSTEM_KB_TYPE = "SYSTEM";

  private final KbAclMapper kbAclMapper;
  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final DocumentMapper documentMapper;
  private final OrgMemberMapper orgMemberMapper;
  private final RagforgeMetrics metrics;

  private static final String ORG_VISIBILITY = "ORG";

  public boolean canRead(Long kbId) {
    if (kbId == null) {
      return false;
    }
    RagAuthContext context = RagAuthContextHolder.get();
    if (context == null) {
      return false;
    }
    if (context.isAdmin() && AdminOverrideHolder.isActive()) {
      return isNonSystemKb(kbId);
    }
    KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
    if (kb == null || isSystem(kb)) {
      return false;
    }
    // 模型 Y：移除全域公开(PUBLIC)——不再有"任何人可读公开库"的跨组织放行。
    if (isOwner(kb, context)) {
      return true;
    }
    if (canReadOrgKb(kb, context)) {
      return true;
    }
    return readableKbIds(context).contains(kbId);
  }

  public boolean canWrite(Long kbId) {
    if (kbId == null) {
      return false;
    }
    RagAuthContext context = RagAuthContextHolder.get();
    if (context == null) {
      return false;
    }
    if (context.isAdmin() && AdminOverrideHolder.isActive()) {
      return isNonSystemKb(kbId);
    }
    KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
    if (kb == null || isSystem(kb)) {
      return false;
    }
    if (isOwner(kb, context)) {
      return true;
    }
    if (canManageOrgKb(kb, context)) {
      return true;
    }
    Set<Long> writable = context.writableKbIds();
    if (writable != null && !writable.isEmpty()) {
      return writable.contains(kbId);
    }
    return context.userId() != null && kbAclMapper.findWritableKbIds(context.userId()).contains(kbId);
  }

  public boolean canAdmin(Long kbId) {
    if (kbId == null) {
      return false;
    }
    RagAuthContext context = RagAuthContextHolder.get();
    if (context == null) {
      return false;
    }
    if (context.isAdmin() && AdminOverrideHolder.isActive()) {
      return isNonSystemKb(kbId);
    }
    KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
    if (kb == null || isSystem(kb)) {
      return false;
    }
    if (isOwner(kb, context)) {
      return true;
    }
    if (canManageOrgKb(kb, context)) {
      return true;
    }
    return context.userId() != null && kbAclMapper.findAdminKbIds(context.userId()).contains(kbId);
  }

  public boolean canReadDocument(Long docId) {
    Long kbId = kbIdForDocument(docId);
    return kbId != null && canRead(kbId);
  }

  public boolean canWriteDocument(Long docId) {
    Long kbId = kbIdForDocument(docId);
    return kbId != null && canWrite(kbId);
  }

  public Set<Long> filterReadable(Collection<Long> kbIds) {
    if (kbIds == null || kbIds.isEmpty()) {
      return Set.of();
    }
    Set<Long> requested = new LinkedHashSet<>(kbIds);
    Set<Long> allowed = allReadableKbIds();
    Set<Long> filtered = new LinkedHashSet<>(requested);
    filtered.retainAll(allowed);
    if (filtered.size() != requested.size()) {
      metrics.recordKbAccessDenied("filter_readable");
      log.warn("KB_ACCESS_DENIED requestedKbIds={} readableKbIds={} filteredKbIds={}", requested, allowed, filtered);
    }
    return filtered;
  }

  public Set<Long> allReadableKbIds() {
    RagAuthContext context = RagAuthContextHolder.get();
    if (context == null) {
      return Set.of();
    }
    if (context.isAdmin() && AdminOverrideHolder.isActive()) {
      return new LinkedHashSet<>(
          knowledgeBaseMapper.selectList(
                  new LambdaQueryWrapper<KnowledgeBase>()
                      .ne(KnowledgeBase::getKbType, SYSTEM_KB_TYPE)
                      .or()
                      .isNull(KnowledgeBase::getKbType))
              .stream()
              .map(KnowledgeBase::getId)
              .toList());
    }
    // 模型 Y：默认（含未破玻璃的 ADMIN）= 自有库 ∪ 组织可见库 ∪ (claims/acl/key 授权库)。
    // 不再并入全站 PUBLIC —— 跨组织共享改由 API Key 授权(readableKbIds)实现。
    Set<Long> ids = new LinkedHashSet<>(readableKbIds(context));
    ids.addAll(ownedKbIds(context));
    ids.addAll(orgReadableKbIds(context));
    return ids;
  }

  /** 组织库读权限：管理者(OWNER/ADMIN)可读本组织任意库；普通成员可读 ORG 可见库。 */
  private boolean canReadOrgKb(KnowledgeBase kb, RagAuthContext context) {
    if (kb.getOrgId() == null || context.userId() == null) {
      return false;
    }
    if (orgMemberMapper.isOrgAdmin(kb.getOrgId(), context.userId())) {
      return true;
    }
    return ORG_VISIBILITY.equalsIgnoreCase(kb.getVisibility())
        && orgMemberMapper.isMember(kb.getOrgId(), context.userId());
  }

  /** 组织库写/管理权限：仅本组织 OWNER/ADMIN。 */
  private boolean canManageOrgKb(KnowledgeBase kb, RagAuthContext context) {
    return kb.getOrgId() != null
        && context.userId() != null
        && orgMemberMapper.isOrgAdmin(kb.getOrgId(), context.userId());
  }

  /** 我可见的组织库集合：所属组织的 ORG 库 ∪ 我作为管理者组织的任意库。 */
  private Set<Long> orgReadableKbIds(RagAuthContext context) {
    if (context.userId() == null) {
      return Set.of();
    }
    Set<Long> ids = new LinkedHashSet<>();
    List<Long> memberOrgIds = orgMemberMapper.findMemberOrgIds(context.userId());
    if (memberOrgIds != null && !memberOrgIds.isEmpty()) {
      ids.addAll(
          knowledgeBaseMapper.selectList(
                  new LambdaQueryWrapper<KnowledgeBase>()
                      .in(KnowledgeBase::getOrgId, memberOrgIds)
                      .eq(KnowledgeBase::getVisibility, ORG_VISIBILITY)
                      .ne(KnowledgeBase::getKbType, SYSTEM_KB_TYPE))
              .stream()
              .map(KnowledgeBase::getId)
              .toList());
    }
    List<Long> adminOrgIds = orgMemberMapper.findAdminOrgIds(context.userId());
    if (adminOrgIds != null && !adminOrgIds.isEmpty()) {
      ids.addAll(
          knowledgeBaseMapper.selectList(
                  new LambdaQueryWrapper<KnowledgeBase>()
                      .in(KnowledgeBase::getOrgId, adminOrgIds)
                      .ne(KnowledgeBase::getKbType, SYSTEM_KB_TYPE))
              .stream()
              .map(KnowledgeBase::getId)
              .toList());
    }
    return ids;
  }

  private Set<Long> ownedKbIds(RagAuthContext context) {
    if (context.userId() == null) {
      return Set.of();
    }
    return new LinkedHashSet<>(
        knowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<KnowledgeBase>()
                    .eq(KnowledgeBase::getOwnerUserId, context.userId())
                    .ne(KnowledgeBase::getKbType, SYSTEM_KB_TYPE))
            .stream()
            .map(KnowledgeBase::getId)
            .toList());
  }

  private boolean isOwner(KnowledgeBase kb, RagAuthContext context) {
    return kb.getOwnerUserId() != null
        && context.userId() != null
        && kb.getOwnerUserId().equals(context.userId());
  }

  private boolean isSystem(KnowledgeBase kb) {
    return SYSTEM_KB_TYPE.equalsIgnoreCase(kb.getKbType());
  }

  private Set<Long> readableKbIds(RagAuthContext context) {
    if (SERVICE_ACCOUNT.equals(context.ragRole())) {
      return copy(context.readableKbIds());
    }
    if (context.readableKbIds() != null && !context.readableKbIds().isEmpty()) {
      return copy(context.readableKbIds());
    }
    if (context.userId() == null) {
      return Set.of();
    }
    List<Long> fallbackIds = kbAclMapper.findReadableKbIds(context.userId());
    return fallbackIds == null ? Set.of() : new LinkedHashSet<>(fallbackIds);
  }

  private Long kbIdForDocument(Long docId) {
    if (docId == null) {
      return null;
    }
    Document document = documentMapper.selectById(docId);
    return document == null ? null : document.getKbId();
  }

  private boolean isNonSystemKb(Long kbId) {
    KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
    return kb != null && !SYSTEM_KB_TYPE.equalsIgnoreCase(kb.getKbType());
  }

  private static Set<Long> copy(Set<Long> ids) {
    return ids == null ? Set.of() : new LinkedHashSet<>(ids);
  }
}
