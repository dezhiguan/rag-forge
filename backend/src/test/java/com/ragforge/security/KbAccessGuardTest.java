package com.ragforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KbAclMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.mapper.OrgMemberMapper;
import com.ragforge.metrics.RagforgeMetrics;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.KnowledgeBase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KbAccessGuardTest {

  @Mock private KbAclMapper kbAclMapper;
  @Mock private DocumentMapper documentMapper;
  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private OrgMemberMapper orgMemberMapper;

  private SimpleMeterRegistry meterRegistry;
  private KbAccessGuard guard;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    guard =
        new KbAccessGuard(
            kbAclMapper,
            knowledgeBaseMapper,
            documentMapper,
            orgMemberMapper,
            new RagforgeMetrics(meterRegistry));
  }

  @AfterEach
  void tearDown() {
    RagAuthContextHolder.clear();
    AdminOverrideHolder.clear();
  }

  @Test
  void kbViewer_canReadOnlyReadableKbIds() {
    KnowledgeBase kb1 = new KnowledgeBase();
    kb1.setId(1L);
    kb1.setKbType("USER");
    when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb1);
    RagAuthContextHolder.set(new RagAuthContext(7L, "KB_VIEWER", Set.of(1L), Set.of(), Set.of(), "user", "7"));

    assertThat(guard.canRead(1L)).isTrue();
    assertThat(guard.canRead(2L)).isFalse();
  }

  @Test
  void admin_defaultCannotReadOthersPrivateKb_butBreakGlassCanReadNonSystem() {
    KnowledgeBase normal = new KnowledgeBase();
    normal.setId(10L);
    normal.setKbType("USER");
    normal.setOwnerUserId(99L); // 属于他人
    normal.setVisibility("PRIVATE");
    KnowledgeBase system = new KnowledgeBase();
    system.setId(11L);
    system.setKbType("SYSTEM");
    when(knowledgeBaseMapper.selectById(10L)).thenReturn(normal);
    when(knowledgeBaseMapper.selectById(11L)).thenReturn(system);
    RagAuthContextHolder.set(new RagAuthContext(1L, "ADMIN", Set.of(), Set.of(), Set.of(), "user", "1"));

    // 默认：ADMIN 不读他人私有库（对齐多租户隐私边界）
    assertThat(guard.canRead(10L)).isFalse();

    // 破玻璃后：可读全部非 SYSTEM 库；SYSTEM 仍禁
    try {
      AdminOverrideHolder.activate("support-debug");
      assertThat(guard.canRead(10L)).isTrue();
      assertThat(guard.canRead(11L)).isFalse();
    } finally {
      AdminOverrideHolder.clear();
    }
  }

  @Test
  void filterReadable_recordsAuditMetricForDeniedDiff() {
    RagAuthContextHolder.set(new RagAuthContext(7L, "KB_VIEWER", Set.of(1L), Set.of(), Set.of(), "user", "7"));

    assertThat(guard.filterReadable(List.of(1L, 2L))).containsExactly(1L);
    assertThat(meterRegistry.counter("ragforge.authz.kb_access_denied", "operation", "filter_readable").count())
        .isEqualTo(1.0);
    assertThat(meterRegistry.counter("ragforge.kb_access_denied", "operation", "filter_readable").count())
        .isEqualTo(1.0);
  }

  @Test
  void serviceAccount_limitedByAllowedKbIds() {
    KnowledgeBase kb3 = new KnowledgeBase();
    kb3.setId(3L);
    kb3.setKbType("USER");
    when(knowledgeBaseMapper.selectById(3L)).thenReturn(kb3);
    RagAuthContextHolder.set(new RagAuthContext(null, "SERVICE_ACCOUNT", Set.of(3L), Set.of(3L), Set.of(), "service_account", "sa"));

    assertThat(guard.canRead(3L)).isTrue();
    assertThat(guard.canRead(4L)).isFalse();
  }

  @Test
  void owner_canReadWriteAndAdminPrivateKb() {
    when(knowledgeBaseMapper.selectById(10L)).thenReturn(kb(10L, 7L, null, "PRIVATE", "USER"));
    RagAuthContextHolder.set(new RagAuthContext(7L, "KB_ADMIN", Set.of(), Set.of(), Set.of(), "user", "7"));

    assertThat(guard.canRead(10L)).isTrue();
    assertThat(guard.canWrite(10L)).isTrue();
    assertThat(guard.canAdmin(10L)).isTrue();
  }

  @Test
  void orgMember_canReadOrgVisibleKbButCannotWrite() {
    when(knowledgeBaseMapper.selectById(20L)).thenReturn(kb(20L, 99L, 300L, "ORG", "USER"));
    when(orgMemberMapper.isOrgAdmin(300L, 7L)).thenReturn(false);
    when(orgMemberMapper.isMember(300L, 7L)).thenReturn(true);
    when(kbAclMapper.findWritableKbIds(7L)).thenReturn(List.of());
    RagAuthContextHolder.set(new RagAuthContext(7L, "KB_VIEWER", Set.of(), Set.of(), Set.of(), "user", "7"));

    assertThat(guard.canRead(20L)).isTrue();
    assertThat(guard.canWrite(20L)).isFalse();
  }

  @Test
  void orgAdmin_canManageAnyKbInOrg() {
    when(knowledgeBaseMapper.selectById(30L)).thenReturn(kb(30L, 99L, 300L, "PRIVATE", "USER"));
    when(orgMemberMapper.isOrgAdmin(300L, 7L)).thenReturn(true);
    RagAuthContextHolder.set(new RagAuthContext(7L, "KB_ADMIN", Set.of(), Set.of(), Set.of(), "user", "7"));

    assertThat(guard.canRead(30L)).isTrue();
    assertThat(guard.canWrite(30L)).isTrue();
    assertThat(guard.canAdmin(30L)).isTrue();
  }

  @Test
  void aclFallback_grantsReadWriteAndAdminWhenClaimsAreEmpty() {
    when(knowledgeBaseMapper.selectById(40L)).thenReturn(kb(40L, 99L, null, "PRIVATE", "USER"));
    when(kbAclMapper.findReadableKbIds(7L)).thenReturn(List.of(40L));
    when(kbAclMapper.findWritableKbIds(7L)).thenReturn(List.of(40L));
    when(kbAclMapper.findAdminKbIds(7L)).thenReturn(List.of(40L));
    RagAuthContextHolder.set(new RagAuthContext(7L, "KB_VIEWER", Set.of(), Set.of(), Set.of(), "user", "7"));

    assertThat(guard.canRead(40L)).isTrue();
    assertThat(guard.canWrite(40L)).isTrue();
    assertThat(guard.canAdmin(40L)).isTrue();
  }

  @Test
  void documentPermissions_delegateToDocumentKnowledgeBase() {
    Document document = new Document();
    document.setId(501L);
    document.setKbId(50L);
    when(documentMapper.selectById(501L)).thenReturn(document);
    when(knowledgeBaseMapper.selectById(50L)).thenReturn(kb(50L, 7L, null, "PRIVATE", "USER"));
    RagAuthContextHolder.set(new RagAuthContext(7L, "KB_ADMIN", Set.of(), Set.of(), Set.of(), "user", "7"));

    assertThat(guard.canReadDocument(501L)).isTrue();
    assertThat(guard.canWriteDocument(501L)).isTrue();
    assertThat(guard.canReadDocument(null)).isFalse();
  }

  @Test
  void allReadableKbIds_combinesClaimsOwnedPublicAndOrgKbs() {
    when(knowledgeBaseMapper.selectList(any()))
        .thenReturn(
            List.of(kb(61L, 7L, null, "PRIVATE", "USER")),
            List.of(kb(62L, 99L, null, "PUBLIC", "USER")),
            List.of(kb(63L, 99L, 301L, "ORG", "USER")),
            List.of(kb(64L, 99L, 302L, "PRIVATE", "USER")));
    when(orgMemberMapper.findMemberOrgIds(7L)).thenReturn(List.of(301L));
    when(orgMemberMapper.findAdminOrgIds(7L)).thenReturn(List.of(302L));
    RagAuthContextHolder.set(new RagAuthContext(7L, "KB_VIEWER", Set.of(60L), Set.of(), Set.of(), "user", "7"));

    assertThat(guard.allReadableKbIds()).containsExactly(60L, 61L, 62L, 63L, 64L);
  }

  @Test
  void canRead_nullKbId_returnsFalse() {
    RagAuthContextHolder.set(new RagAuthContext(1L, "KB_VIEWER", Set.of(1L), Set.of(), Set.of(), "user", "1"));
    assertThat(guard.canRead(null)).isFalse();
  }

  @Test
  void canRead_noAuthContext_returnsFalse() {
    // no RagAuthContextHolder.set()
    assertThat(guard.canRead(1L)).isFalse();
  }

  @Test
  void canRead_publicKb_returnsTrue() {
    when(knowledgeBaseMapper.selectById(70L)).thenReturn(kb(70L, 99L, null, "PUBLIC", "USER"));
    RagAuthContextHolder.set(new RagAuthContext(7L, "KB_VIEWER", Set.of(), Set.of(), Set.of(), "user", "7"));

    assertThat(guard.canRead(70L)).isTrue();
  }

  @Test
  void canRead_systemKb_returnsFalse() {
    when(knowledgeBaseMapper.selectById(100L)).thenReturn(kb(100L, 99L, null, "PUBLIC", "SYSTEM"));
    RagAuthContextHolder.set(new RagAuthContext(7L, "KB_VIEWER", Set.of(), Set.of(), Set.of(), "user", "7"));

    assertThat(guard.canRead(100L)).isFalse();
  }

  @Test
  void canWrite_nullKbId_returnsFalse() {
    RagAuthContextHolder.set(new RagAuthContext(1L, "KB_ADMIN", Set.of(), Set.of(1L), Set.of(), "user", "1"));
    assertThat(guard.canWrite(null)).isFalse();
  }

  @Test
  void canWrite_noAuthContext_returnsFalse() {
    assertThat(guard.canWrite(1L)).isFalse();
  }

  @Test
  void canAdmin_nullKbId_returnsFalse() {
    RagAuthContextHolder.set(new RagAuthContext(1L, "KB_ADMIN", Set.of(), Set.of(1L), Set.of(), "user", "1"));
    assertThat(guard.canAdmin(null)).isFalse();
  }

  @Test
  void canAdmin_noAuthContext_returnsFalse() {
    assertThat(guard.canAdmin(1L)).isFalse();
  }

  @Test
  void canWriteDocument_missingDocument_returnsFalse() {
    when(documentMapper.selectById(999L)).thenReturn(null);
    RagAuthContextHolder.set(new RagAuthContext(7L, "KB_ADMIN", Set.of(), Set.of(), Set.of(), "user", "7"));

    assertThat(guard.canWriteDocument(999L)).isFalse();
  }

  @Test
  void filterReadable_emptyInput_returnsEmpty() {
    RagAuthContextHolder.set(new RagAuthContext(7L, "KB_VIEWER", Set.of(), Set.of(), Set.of(), "user", "7"));

    assertThat(guard.filterReadable(List.of())).isEmpty();
  }

  @Test
  void allReadableKbIds_noUserId_returnsOnlyClaims() {
    RagAuthContextHolder.set(new RagAuthContext(null, "SERVICE_ACCOUNT", Set.of(5L), Set.of(), Set.of(), "service_account", "sa"));

    Set<Long> ids = guard.allReadableKbIds();
    assertThat(ids).contains(5L);
  }

  private static KnowledgeBase kb(Long id, Long ownerUserId, Long orgId, String visibility, String type) {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(id);
    kb.setOwnerUserId(ownerUserId);
    kb.setOrgId(orgId);
    kb.setVisibility(visibility);
    kb.setKbType(type);
    return kb;
  }
}
