package com.ragforge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.ragforge.common.BizException;
import com.ragforge.mapper.ApiKeyMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.mapper.OrgMemberMapper;
import com.ragforge.model.dto.CreateApiKeyCommand;
import com.ragforge.model.entity.ApiKey;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.security.AdminOverrideHolder;
import com.ragforge.security.ApiKeyInterceptor;
import com.ragforge.security.OrgContextHolder;
import com.ragforge.service.impl.ApiKeyServiceImpl;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceImplTest {

  @Mock private ApiKeyMapper apiKeyMapper;
  @Mock private ApiKeyInterceptor apiKeyInterceptor;
  @Mock private OrgMemberMapper orgMemberMapper;
  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;

  @InjectMocks private ApiKeyServiceImpl apiKeyService;

  @BeforeEach
  void setUpOrg() {
    OrgContextHolder.set(16L);
    com.ragforge.security.RagAuthContextHolder.set(
        new com.ragforge.security.RagAuthContext(
            7L, "USER", java.util.Set.of(16L), java.util.Set.of(16L), java.util.Set.of(), "USER", "7"));
    lenient().when(orgMemberMapper.isOrgAdmin(16L, 7L)).thenReturn(true);
  }

  @AfterEach
  void clearContext() {
    OrgContextHolder.clear();
    AdminOverrideHolder.clear();
    com.ragforge.security.RagAuthContextHolder.clear();
  }

  @Test
  void listForCurrentOrg_returnsCurrentOrgKeys() {
    ApiKey key = new ApiKey();
    key.setId(1L);
    when(apiKeyMapper.selectList(any())).thenReturn(List.of(key));

    assertThat(apiKeyService.listForCurrentOrg()).containsExactly(key);
    verify(apiKeyMapper).selectList(any());
  }

  @Test
  void listForCurrentOrg_withoutOrg_returnsEmptyList() {
    OrgContextHolder.clear();

    assertThat(apiKeyService.listForCurrentOrg()).isEmpty();
    verify(apiKeyMapper, never()).selectList(any());
  }

  @Test
  void governanceSearch_requiresAdminBreakglassAndPreciseQuery() {
    assertThatThrownBy(() -> apiKeyService.governanceSearch("abc"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(403));

    setAdminBreakglass();
    assertThatThrownBy(() -> apiKeyService.governanceSearch("ab"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(400));
  }

  @Test
  void governanceSearch_returnsMatchingKeysWhenAdminBreakglass() {
    setAdminBreakglass();
    ApiKey key = new ApiKey();
    key.setId(3L);
    when(apiKeyMapper.selectList(any())).thenReturn(List.of(key));

    assertThat(apiKeyService.governanceSearch(" sk-rf-abc ")).containsExactly(key);
    verify(apiKeyMapper).selectList(any());
  }

  @Test
  void revokeWithReason_requiresBreakglassReasonAndExistingKey() {
    assertThatThrownBy(() -> apiKeyService.revokeWithReason(1L, "risk"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(403));

    setAdminBreakglass();
    assertThatThrownBy(() -> apiKeyService.revokeWithReason(1L, " "))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(400));

    when(apiKeyMapper.selectById(1L)).thenReturn(null);
    assertThatThrownBy(() -> apiKeyService.revokeWithReason(1L, "risk"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(404));
  }

  @Test
  void revokeWithReason_disablesKeyAndResetsCache() {
    setAdminBreakglass();
    ApiKey existing = new ApiKey();
    existing.setId(1L);
    existing.setOrgId(16L);
    when(apiKeyMapper.selectById(1L)).thenReturn(existing);

    ApiKey result = apiKeyService.revokeWithReason(1L, "risk");

    assertThat(result).isSameAs(existing);
    verify(apiKeyMapper).update(any(), any());
    verify(apiKeyInterceptor).resetKeyCache();
  }

  @Test
  void rename_validatesKeyAndNameThenUpdates() {
    when(apiKeyMapper.selectById(12L)).thenReturn(null);
    assertThatThrownBy(() -> apiKeyService.rename(12L, "new"))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(404));

    ApiKey existing = new ApiKey();
    existing.setId(12L);
    existing.setOrgId(16L);
    when(apiKeyMapper.selectById(12L)).thenReturn(existing);
    assertThatThrownBy(() -> apiKeyService.rename(12L, " "))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(400));

    ApiKey renamed = apiKeyService.rename(12L, " new name ");

    assertThat(renamed.getKeyName()).isEqualTo("new name");
    verify(apiKeyMapper).update(any(), any());
  }

  private CreateApiKeyCommand cmd(String name) {
    return new CreateApiKeyCommand(name, null, null, null, null);
  }

  @Test
  void create_defaultsOrgAllReadAndGeneratesHashPrefix() {
    ApiKey created = apiKeyService.create(cmd("integration"));

    ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
    verify(apiKeyMapper).insert(captor.capture());
    verify(apiKeyInterceptor).resetKeyCache();

    ApiKey saved = captor.getValue();
    assertThat(saved.getKeyName()).isEqualTo("integration");
    assertThat(saved.getApiKey()).startsWith("sk-rf-");
    assertThat(saved.getApiKey()).hasSize("sk-rf-".length() + 48);
    assertThat(saved.getEnabled()).isTrue();
    assertThat(saved.getRateLimit()).isEqualTo(100);
    assertThat(saved.getOrgId()).isEqualTo(16L);
    // 默认范围/级别
    assertThat(saved.getScopeMode()).isEqualTo("ORG_ALL");
    assertThat(saved.getAccessLevel()).isEqualTo("READ");
    // hash + 前缀
    assertThat(saved.getKeyHash()).hasSize(64).isEqualTo(ApiKeyServiceImpl.sha256Hex(saved.getApiKey()));
    assertThat(saved.getKeyPrefix()).isEqualTo(saved.getApiKey().substring(0, 12));
    assertThat(saved.getExpiresAt()).isNull();
    assertThat(created.getApiKey()).isEqualTo(saved.getApiKey());
  }

  @Test
  void create_blankName_throws400() {
    assertThatThrownBy(() -> apiKeyService.create(cmd("  ")))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(400));
    verify(apiKeyMapper, never()).insert(any(ApiKey.class));
  }

  @Test
  void create_readWriteRequestForcedToRead() {
    apiKeyService.create(new CreateApiKeyCommand("k", "ORG_ALL", null, "READ_WRITE", null));
    ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
    verify(apiKeyMapper).insert(captor.capture());
    assertThat(captor.getValue().getAccessLevel()).isEqualTo("READ");
  }

  @Test
  void create_withExpiresAt_persisted() {
    java.time.LocalDateTime exp = java.time.LocalDateTime.now().plusDays(30);
    apiKeyService.create(new CreateApiKeyCommand("k", "ORG_ALL", null, "READ", exp));
    ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
    verify(apiKeyMapper).insert(captor.capture());
    assertThat(captor.getValue().getExpiresAt()).isEqualTo(exp);
  }

  @Test
  void create_kbListEmpty_throws400() {
    assertThatThrownBy(
            () -> apiKeyService.create(new CreateApiKeyCommand("k", "KB_LIST", List.of(), "READ", null)))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(400));
    verify(apiKeyMapper, never()).insert(any(ApiKey.class));
  }

  @Test
  void create_kbListBelongingToOrg_ok() {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(100L);
    kb.setOrgId(16L);
    when(knowledgeBaseMapper.selectBatchIds(any())).thenReturn(List.of(kb));

    apiKeyService.create(new CreateApiKeyCommand("k", "KB_LIST", List.of(100L), "READ", null));

    ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
    verify(apiKeyMapper).insert(captor.capture());
    ApiKey saved = captor.getValue();
    assertThat(saved.getScopeMode()).isEqualTo("KB_LIST");
    assertThat(saved.getAllowedKbIds()).isEqualTo("[100]");
  }

  @Test
  void create_kbListNotBelongingToOrg_throws403() {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(200L);
    kb.setOrgId(999L); // 他组织
    when(knowledgeBaseMapper.selectBatchIds(any())).thenReturn(List.of(kb));

    assertThatThrownBy(
            () -> apiKeyService.create(new CreateApiKeyCommand("k", "KB_LIST", List.of(200L), "READ", null)))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(403));
    verify(apiKeyMapper, never()).insert(any(ApiKey.class));
  }

  @Test
  void create_kbListMissingKb_throws404() {
    when(knowledgeBaseMapper.selectBatchIds(any())).thenReturn(List.of()); // 查不到

    assertThatThrownBy(
            () -> apiKeyService.create(new CreateApiKeyCommand("k", "KB_LIST", List.of(300L), "READ", null)))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(404));
  }

  @Test
  void create_nonOrgAdmin_throws403() {
    when(orgMemberMapper.isOrgAdmin(16L, 7L)).thenReturn(false);
    assertThatThrownBy(() -> apiKeyService.create(cmd("k")))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(403));
  }

  private void setAdminBreakglass() {
    com.ragforge.security.RagAuthContextHolder.set(
        new com.ragforge.security.RagAuthContext(
            1L, "ADMIN", java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), "USER", "1"));
    AdminOverrideHolder.activate("test");
  }

  @Test
  void enable_missingKey_throws404() {
    when(apiKeyMapper.selectById(99L)).thenReturn(null);

    assertThatThrownBy(() -> apiKeyService.enable(99L, false))
        .isInstanceOf(BizException.class)
        .satisfies(
            ex -> assertThat(((BizException) ex).getCode()).isEqualTo(404));
    verify(apiKeyMapper, never()).updateById(org.mockito.ArgumentMatchers.<ApiKey>any());
    verify(apiKeyMapper, never()).update(any(), any());
    verify(apiKeyInterceptor, never()).resetKeyCache();
  }

  @Test
  void enable_existingKey_updatesOnlyEnabledAndResetsCache() {
    ApiKey existing = new ApiKey();
    existing.setId(8L);
    existing.setApiKey("sk-rf-abc");
    existing.setEnabled(false);
    existing.setOrgId(16L);
    existing.setScopes("[\"rag:search\"]");
    existing.setAllowedKbIds("[16]");
    when(apiKeyMapper.selectById(8L)).thenReturn(existing);

    ApiKey updated = apiKeyService.enable(8L, true);

    assertThat(updated.getEnabled()).isTrue();
    verify(apiKeyMapper, never()).updateById(org.mockito.ArgumentMatchers.<ApiKey>any());
    verify(apiKeyMapper).update(any(), any());
    verify(apiKeyInterceptor).resetKeyCache();
  }

  @Test
  void delete_missingKey_throws404() {
    when(apiKeyMapper.selectById(7L)).thenReturn(null);

    assertThatThrownBy(() -> apiKeyService.delete(7L))
        .isInstanceOf(BizException.class)
        .satisfies(
            ex -> assertThat(((BizException) ex).getCode()).isEqualTo(404));
    verify(apiKeyMapper, never()).deleteById(org.mockito.ArgumentMatchers.<Long>any());
    verify(apiKeyInterceptor, never()).resetKeyCache();
  }

  @Test
  void delete_existingKey_deletesAndResetsCache() {
    ApiKey existing = new ApiKey();
    existing.setId(7L);
    existing.setApiKey("sk-rf-abc");
    existing.setOrgId(16L);
    when(apiKeyMapper.selectById(7L)).thenReturn(existing);

    apiKeyService.delete(7L);

    verify(apiKeyMapper).deleteById(7L);
    verify(apiKeyInterceptor).resetKeyCache();
  }
}
