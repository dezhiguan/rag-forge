package com.ragforge.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KbAclMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
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
  private final RagforgeMetrics metrics;

  private static final String PUBLIC_VISIBILITY = "PUBLIC";

  public boolean canRead(Long kbId) {
    if (kbId == null) {
      return false;
    }
    RagAuthContext context = RagAuthContextHolder.get();
    if (context == null) {
      return false;
    }
    if (context.isAdmin()) {
      return isNonSystemKb(kbId);
    }
    KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
    if (kb == null || isSystem(kb)) {
      return false;
    }
    if (isOwner(kb, context) || isPublic(kb)) {
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
    if (context.isAdmin()) {
      return isNonSystemKb(kbId);
    }
    KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
    if (kb == null || isSystem(kb)) {
      return false;
    }
    if (isOwner(kb, context)) {
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
    if (context.isAdmin()) {
      return isNonSystemKb(kbId);
    }
    KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
    if (kb == null || isSystem(kb)) {
      return false;
    }
    if (isOwner(kb, context)) {
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
    if (context.isAdmin()) {
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
    // 普通用户：自有库 ∪ public 库 ∪ (claims/acl 授权库)
    Set<Long> ids = new LinkedHashSet<>(readableKbIds(context));
    ids.addAll(ownedKbIds(context));
    ids.addAll(publicKbIds());
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

  private Set<Long> publicKbIds() {
    return new LinkedHashSet<>(
        knowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<KnowledgeBase>()
                    .eq(KnowledgeBase::getVisibility, PUBLIC_VISIBILITY)
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

  private boolean isPublic(KnowledgeBase kb) {
    return PUBLIC_VISIBILITY.equalsIgnoreCase(kb.getVisibility());
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
