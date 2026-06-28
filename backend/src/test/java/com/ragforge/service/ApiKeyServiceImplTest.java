package com.ragforge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.common.BizException;
import com.ragforge.security.ApiKeyInterceptor;
import com.ragforge.mapper.ApiKeyMapper;
import com.ragforge.model.entity.ApiKey;
import com.ragforge.service.impl.ApiKeyServiceImpl;
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

  @InjectMocks private ApiKeyServiceImpl apiKeyService;

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
    assertThat(created.getApiKey()).isEqualTo(saved.getApiKey());
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
    when(apiKeyMapper.selectById(7L)).thenReturn(existing);

    apiKeyService.delete(7L);

    verify(apiKeyMapper).deleteById(7L);
    verify(apiKeyInterceptor).resetKeyCache();
  }
}
