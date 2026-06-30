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
import com.ragforge.mapper.OrgMemberMapper;
import com.ragforge.model.entity.ApiKey;
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

  @Test
  void create_generatesSkRfKeyAndResetsCache() {
    ApiKey created = apiKeyService.create("integration");

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
    assertThat(created.getApiKey()).isEqualTo(saved.getApiKey());
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
