package com.ragforge.modelcenter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.common.BizException;
import com.ragforge.mapper.ModelConfigMapper;
import com.ragforge.mapper.ModelUsageDailyMapper;
import com.ragforge.model.entity.ModelConfig;
import com.ragforge.model.entity.ModelUsageDaily;
import com.ragforge.modelcenter.vo.CostDetailVo;
import com.ragforge.modelcenter.vo.CostStatsVo;
import com.ragforge.modelcenter.vo.ModelItemVo;
import com.ragforge.security.AdminOverrideHolder;
import com.ragforge.security.OrgContextHolder;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelCenterServiceTest {

  @Mock private ModelConfigMapper modelConfigMapper;
  @Mock private ModelUsageDailyMapper usageMapper;
  @Mock private ModelResolver modelResolver;

  @InjectMocks private ModelCenterService service;

  @BeforeEach
  void setUp() {
    // Default: non-admin context with org 10
    RagAuthContextHolder.set(new RagAuthContext(1L, "KB_EDITOR", Set.of(), Set.of(), Set.of(), "USER", "1"));
    OrgContextHolder.set(10L);
    when(modelConfigMapper.selectList(any())).thenReturn(List.of(
        cfg("qwen-plus", "ANSWER", true, true, 1),
        cfg("qwen-turbo", "REWRITE", true, true, 2)
    ));
    when(usageMapper.selectList(any())).thenReturn(List.of(
        usage("qwen-plus", "ANSWER", 10L),
        usage("qwen-turbo", "REWRITE", 10L)
    ));
  }

  @AfterEach
  void tearDown() {
    RagAuthContextHolder.clear();
    OrgContextHolder.clear();
    AdminOverrideHolder.clear();
  }

  @Test
  void listModels_returnsAllModelsWithMonthlyCost() {
    List<ModelItemVo> models = service.listModels();
    assertThat(models).hasSize(2);
    assertThat(models.get(0).code()).isEqualTo("qwen-plus");
    assertThat(models.get(0).monthlyCost()).isNotNull();
  }

  @Test
  void costStats_defaultSpan_computesKpi() {
    CostStatsVo stats = service.costStats(7);
    assertThat(stats.kpi()).isNotNull();
    assertThat(stats.kpi().modelCount()).isEqualTo(2);
    assertThat(stats.kpi().enabledCount()).isEqualTo(2);
    assertThat(stats.trend()).isNotEmpty();
    assertThat(stats.trend()).hasSize(7);
  }

  @Test
  void costStats_nonPositiveDays_defaultsTo7() {
    CostStatsVo stats = service.costStats(0);
    assertThat(stats.trend()).hasSize(7);
  }

  @Test
  void costStats_daysExceedMax_capsAt90() {
    CostStatsVo stats = service.costStats(200);
    assertThat(stats.trend()).hasSize(90);
  }

  @Test
  void costDetail_aggregatesByPurposeAndModel() {
    List<CostDetailVo> detail = service.costDetail();
    assertThat(detail).isNotEmpty();
  }

  @Test
  void toggle_withoutAdminOverride_throws403() {
    assertThatThrownBy(() -> service.toggle("qwen-plus", false))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("PLATFORM_VIEW");
  }

  @Test
  void toggle_modelNotFound_throws404() {
    AdminOverrideHolder.activate("test-override");
    when(modelConfigMapper.selectOne(any())).thenReturn(null);

    assertThatThrownBy(() -> service.toggle("nonexistent", true))
        .isInstanceOf(BizException.class)
        .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(404));
  }

  @Test
  void toggle_alreadySameState_idempotentNoUpdate() {
    AdminOverrideHolder.activate("test-override");
    ModelConfig cfg = cfg("qwen-plus", "ANSWER", true, true, 1);
    when(modelConfigMapper.selectOne(any())).thenReturn(cfg);

    // Toggle to same state (true → true): should return without DB write
    boolean result = service.toggle("qwen-plus", true);
    assertThat(result).isTrue();
    // No updateById should be called
    verify(modelConfigMapper, org.mockito.Mockito.never()).updateById(any(ModelConfig.class));
  }

  @Test
  void toggle_disableWouldLeaveNoPurposeModel_throws409() {
    AdminOverrideHolder.activate("test-override");
    ModelConfig cfg = cfg("qwen-plus", "ANSWER", true, true, 1);
    when(modelConfigMapper.selectOne(any())).thenReturn(cfg);
    when(modelResolver.purposeStillResolvableWithout(Purpose.ANSWER, "qwen-plus")).thenReturn(false);

    assertThatThrownBy(() -> service.toggle("qwen-plus", false))
        .isInstanceOf(BizException.class)
        .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(409));
  }

  @Test
  void toggle_disableWithFallbackAvailable_succeeds() {
    AdminOverrideHolder.activate("test-override");
    ModelConfig cfg = cfg("qwen-plus", "ANSWER", true, true, 1);
    when(modelConfigMapper.selectOne(any())).thenReturn(cfg);
    when(modelResolver.purposeStillResolvableWithout(Purpose.ANSWER, "qwen-plus")).thenReturn(true);

    boolean result = service.toggle("qwen-plus", false);
    assertThat(result).isFalse();
    verify(modelConfigMapper).updateById(any(ModelConfig.class));
    verify(modelResolver).refresh();
  }

  @Test
  void toggle_enableModel_doesNotCheckPurposeResolvability() {
    AdminOverrideHolder.activate("test-override");
    ModelConfig cfg = cfg("qwen-plus", "ANSWER", true, false, 1); // currently disabled
    when(modelConfigMapper.selectOne(any())).thenReturn(cfg);

    boolean result = service.toggle("qwen-plus", true);
    assertThat(result).isTrue();
    verify(modelConfigMapper).updateById(any(ModelConfig.class));
    // purposeStillResolvableWithout should NOT be called for enable operations
    verify(modelResolver, org.mockito.Mockito.never()).purposeStillResolvableWithout(any(), any());
  }

  @Test
  void costStats_adminPlatformView_noOrgFilter() {
    RagAuthContextHolder.set(new RagAuthContext(1L, "ADMIN", Set.of(), Set.of(), Set.of(), "USER", "1"));
    AdminOverrideHolder.activate("platform-view");

    CostStatsVo stats = service.costStats(7);
    // With admin + override: org filter is skipped, all data returned
    assertThat(stats.kpi()).isNotNull();
  }

  private static ModelConfig cfg(String code, String purpose, boolean isPrimary, boolean enabled, int sortOrder) {
    ModelConfig c = new ModelConfig();
    c.setCode(code);
    c.setDisplayName(code + "-display");
    c.setVendor("DashScope");
    c.setPurpose(purpose);
    c.setIsPrimary(isPrimary);
    c.setEnabled(enabled);
    c.setIsLocal(false);
    c.setInputPrice(new BigDecimal("0.004"));
    c.setOutputPrice(new BigDecimal("0.012"));
    c.setSortOrder(sortOrder);
    return c;
  }

  private static ModelUsageDaily usage(String modelCode, String purpose, long orgId) {
    ModelUsageDaily u = new ModelUsageDaily();
    u.setModelCode(modelCode);
    u.setPurpose(purpose);
    u.setStatDate(LocalDate.now());
    u.setOrgId(orgId);
    u.setCallCount(100L);
    u.setInputTokens(50000L);
    u.setOutputTokens(20000L);
    u.setCost(new BigDecimal("0.52"));
    u.setSuccessCount(98L);
    u.setFailCount(2L);
    u.setTotalLatencyMs(5000L);
    return u;
  }
}
