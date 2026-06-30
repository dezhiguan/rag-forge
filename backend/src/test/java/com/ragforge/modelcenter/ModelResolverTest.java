package com.ragforge.modelcenter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.ragforge.mapper.ModelConfigMapper;
import com.ragforge.model.entity.ModelConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ModelResolverTest {

  @Mock private ModelConfigMapper modelConfigMapper;

  @InjectMocks private ModelResolver modelResolver;

  @BeforeEach
  void loadCache() {
    ModelConfig primary = cfg("qwen-plus", "ANSWER", true, true, null);
    ModelConfig fallback = cfg("qwen-turbo", "ANSWER", false, true, null);
    ModelConfig embed = cfg("qwen3-vl-embedding", "EMBEDDING", true, true, null);
    ModelConfig disabledPrimary = cfg("slow-model", "RERANK", true, false, "qwen3-rerank");
    ModelConfig rerankFallback = cfg("qwen3-rerank", "RERANK", false, true, null);

    when(modelConfigMapper.selectList(isNull())).thenReturn(
        List.of(primary, fallback, embed, disabledPrimary, rerankFallback));
    modelResolver.refresh();
  }

  @Test
  void resolve_enabledPrimary_returnsPrimary() {
    ModelConfig result = modelResolver.resolve(Purpose.ANSWER);
    assertThat(result.getCode()).isEqualTo("qwen-plus");
  }

  @Test
  void resolve_disabledPrimary_usesFallbackCode() {
    // slow-model (RERANK primary) is disabled → falls back to qwen3-rerank via fallbackCode
    ModelConfig result = modelResolver.resolve(Purpose.RERANK);
    assertThat(result.getCode()).isEqualTo("qwen3-rerank");
  }

  @Test
  void resolve_noPurposeModels_throwsModelUnavailableException() {
    assertThatThrownBy(() -> modelResolver.resolve(Purpose.OCR))
        .isInstanceOf(ModelUnavailableException.class);
  }

  @Test
  void resolveCodeOrDefault_unavailable_returnsDefault() {
    String code = modelResolver.resolveCodeOrDefault(Purpose.JUDGE, "deepseek-fallback");
    assertThat(code).isEqualTo("deepseek-fallback");
  }

  @Test
  void resolveCodeOrDefault_available_returnsResolvedCode() {
    String code = modelResolver.resolveCodeOrDefault(Purpose.EMBEDDING, "default-embed");
    assertThat(code).isEqualTo("qwen3-vl-embedding");
  }

  @Test
  void findByCode_existingCode_returnsConfig() {
    ModelConfig found = modelResolver.findByCode("qwen-plus");
    assertThat(found).isNotNull();
    assertThat(found.getCode()).isEqualTo("qwen-plus");
  }

  @Test
  void findByCode_unknownCode_returnsNull() {
    assertThat(modelResolver.findByCode("nonexistent")).isNull();
  }

  @Test
  void findByCode_nullCode_returnsNull() {
    assertThat(modelResolver.findByCode(null)).isNull();
  }

  @Test
  void purposeStillResolvableWithout_anotherEnabledExists_returnsTrue() {
    // ANSWER has qwen-plus (primary, enabled) + qwen-turbo (enabled)
    // Disabling qwen-plus still leaves qwen-turbo enabled
    boolean result = modelResolver.purposeStillResolvableWithout(Purpose.ANSWER, "qwen-plus");
    assertThat(result).isTrue();
  }

  @Test
  void purposeStillResolvableWithout_onlyOneEnabled_returnsFalse() {
    // EMBEDDING only has qwen3-vl-embedding; disabling it leaves nothing
    boolean result = modelResolver.purposeStillResolvableWithout(Purpose.EMBEDDING, "qwen3-vl-embedding");
    assertThat(result).isFalse();
  }

  private static ModelConfig cfg(String code, String purpose, boolean isPrimary, boolean enabled, String fallbackCode) {
    ModelConfig c = new ModelConfig();
    c.setCode(code);
    c.setPurpose(purpose);
    c.setIsPrimary(isPrimary);
    c.setEnabled(enabled);
    c.setFallbackCode(fallbackCode);
    c.setSortOrder(1);
    return c;
  }
}
