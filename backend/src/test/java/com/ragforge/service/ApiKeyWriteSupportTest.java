package com.ragforge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import com.ragforge.mapper.ApiKeyMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.mapper.OrgMemberMapper;
import com.ragforge.model.dto.CreateApiKeyCommand;
import com.ragforge.model.entity.ApiKey;
import com.ragforge.security.AdminOverrideHolder;
import com.ragforge.security.ApiKeyInterceptor;
import com.ragforge.security.OrgContextHolder;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import com.ragforge.service.impl.ApiKeyServiceImpl;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 覆盖 API key 写权限支持：accessLevel 归一 + 按级别设 rateLimit。 */
@ExtendWith(MockitoExtension.class)
class ApiKeyWriteSupportTest {

  @Mock private ApiKeyMapper apiKeyMapper;
  @Mock private ApiKeyInterceptor apiKeyInterceptor;
  @Mock private OrgMemberMapper orgMemberMapper;
  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;

  @InjectMocks private ApiKeyServiceImpl apiKeyService;

  @BeforeEach
  void setUpOrg() {
    OrgContextHolder.set(16L);
    RagAuthContextHolder.set(
        new RagAuthContext(7L, "USER", Set.of(16L), Set.of(16L), Set.of(), "USER", "7"));
    lenient().when(orgMemberMapper.isOrgAdmin(16L, 7L)).thenReturn(true);
  }

  @AfterEach
  void clearContext() {
    OrgContextHolder.clear();
    AdminOverrideHolder.clear();
    RagAuthContextHolder.clear();
  }

  private ApiKey createWith(String accessLevel) {
    apiKeyService.create(new CreateApiKeyCommand("k", "ORG_ALL", null, accessLevel, null));
    ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
    verify(apiKeyMapper).insert(captor.capture());
    return captor.getValue();
  }

  @Test
  void writeLevel_persistsWriteAndRaisesRateLimitTo1000() {
    ApiKey saved = createWith("WRITE");
    assertThat(saved.getAccessLevel()).isEqualTo("WRITE");
    assertThat(saved.getRateLimit()).isEqualTo(1000);
  }

  @Test
  void writeLevel_caseInsensitive() {
    ApiKey saved = createWith("write");
    assertThat(saved.getAccessLevel()).isEqualTo("WRITE");
    assertThat(saved.getRateLimit()).isEqualTo(1000);
  }

  @Test
  void readLevel_keepsReadAndDefaultRateLimit() {
    ApiKey saved = createWith("READ");
    assertThat(saved.getAccessLevel()).isEqualTo("READ");
    assertThat(saved.getRateLimit()).isEqualTo(100);
  }

  @Test
  void nullLevel_fallsBackToRead() {
    ApiKey saved = createWith(null);
    assertThat(saved.getAccessLevel()).isEqualTo("READ");
    assertThat(saved.getRateLimit()).isEqualTo(100);
  }

  @Test
  void invalidLevel_fallsBackToRead() {
    ApiKey saved = createWith("HACK");
    assertThat(saved.getAccessLevel()).isEqualTo("READ");
    assertThat(saved.getRateLimit()).isEqualTo(100);
  }
}
