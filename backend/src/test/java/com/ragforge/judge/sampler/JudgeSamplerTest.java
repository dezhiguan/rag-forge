package com.ragforge.judge.sampler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.mapper.JudgeSamplingConfigMapper;
import com.ragforge.model.entity.JudgeSamplingConfig;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class JudgeSamplerTest {

  @Mock private JudgeSamplingConfigMapper configMapper;

  @InjectMocks private JudgeSampler sampler;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(sampler, "samplingFallbackRate", new BigDecimal("0.0"));
  }

  @Test
  void decide_respectsScopePriorityKbOverTenantOverGlobal() {
    when(configMapper.selectList(any()))
        .thenReturn(
            List.of(kbConfig(1L, 10L, new BigDecimal("1.0"), true),
                tenantConfig(2L, "tenant-a", new BigDecimal("0.4"), true),
                globalConfig(3L, null, null, new BigDecimal("0.1"), true)));

    SampleDecision decision =
        sampler.decide(
            new SampleRequest(11L, new Long[] {10L, 20L}, "tenant-a", "PRODUCTION", false));

    assertThat(decision.keep()).isTrue();
    assertThat(decision.configId()).isEqualTo(1L);
    assertThat(decision.reason()).isEqualTo("KEEP_BY_RATE");
  }

  @Test
  void decide_goldenSetAlwaysKeep() {
    SampleDecision decision =
        sampler.decide(new SampleRequest(22L, new Long[] {10L}, "tenant-a", "GOLDEN_SET", false));
    assertThat(decision.keep()).isTrue();
    assertThat(decision.reason()).isEqualTo("KEEP_BY_GOLDEN");
    verify(configMapper, org.mockito.Mockito.never()).selectList(any());
  }

  @Test
  void decide_forceSampleAlwaysKeep() {
    SampleDecision decision =
        sampler.decide(new SampleRequest(33L, new Long[] {10L}, "tenant-a", "PRODUCTION", true));
    assertThat(decision.keep()).isTrue();
    assertThat(decision.reason()).isEqualTo("KEEP_BY_FORCE");
    verify(configMapper, org.mockito.Mockito.never()).selectList(any());
  }

  @Test
  void decide_zeroRateAlwaysSkip() {
    when(configMapper.selectList(any())).thenReturn(List.of(kbConfig(1L, 10L, BigDecimal.ZERO, true)));
    SampleDecision decision =
        sampler.decide(new SampleRequest(44L, new Long[] {10L}, "tenant-a", "PRODUCTION", false));

    assertThat(decision.keep()).isFalse();
    assertThat(decision.reason()).isEqualTo("SKIP_BY_RATE");
  }

  @Test
  void decideOneRateAlwaysKeep() {
    when(configMapper.selectList(any())).thenReturn(List.of(kbConfig(1L, 10L, BigDecimal.ONE, true)));
    SampleDecision decision =
        sampler.decide(new SampleRequest(55L, new Long[] {10L}, "tenant-a", "PRODUCTION", false));

    assertThat(decision.keep()).isTrue();
    assertThat(decision.reason()).isEqualTo("KEEP_BY_RATE");
  }

  @Test
  void decide_disabledConfigAlwaysSkip() {
    when(configMapper.selectList(any()))
        .thenReturn(List.of(kbConfig(1L, 10L, new BigDecimal("0.99"), false)));
    SampleDecision decision =
        sampler.decide(new SampleRequest(66L, new Long[] {10L}, "tenant-a", "PRODUCTION", false));

    assertThat(decision.keep()).isFalse();
    assertThat(decision.reason()).isEqualTo("SKIP_DISABLED");
  }

  @Test
  void decide_randomSamplingConvergesForLargeIterations() {
    when(configMapper.selectList(any())).thenReturn(List.of(kbConfig(1L, 10L, new BigDecimal("0.5"), true)));
    int total = 10_000;
    int keep = 0;
    for (int i = 0; i < total; i++) {
      SampleDecision decision = sampler.decide(new SampleRequest(100L + i, new Long[] {10L}, "tenant-a", "PRODUCTION", false));
      if (decision.keep()) {
        keep++;
      }
    }
    double ratio = keep / (double) total;
    assertThat(ratio).isBetween(0.45, 0.55);
    verify(configMapper, org.mockito.Mockito.times(total)).selectList(any());
  }

  private JudgeSamplingConfig kbConfig(Long id, Long kbId, BigDecimal sampleRate, boolean enabled) {
    JudgeSamplingConfig config = new JudgeSamplingConfig();
    config.setId(id);
    config.setScopeType("KB");
    config.setScopeId(kbId);
    config.setSampleRate(sampleRate);
    config.setEnabled(enabled);
    return config;
  }

  private JudgeSamplingConfig tenantConfig(Long id, String tenant, BigDecimal sampleRate, boolean enabled) {
    JudgeSamplingConfig config = new JudgeSamplingConfig();
    config.setId(id);
    config.setScopeType("TENANT");
    config.setTenantId(tenant);
    config.setSampleRate(sampleRate);
    config.setEnabled(enabled);
    return config;
  }

  private JudgeSamplingConfig globalConfig(
      Long id, Long kbId, String tenant, BigDecimal sampleRate, boolean enabled) {
    JudgeSamplingConfig config = new JudgeSamplingConfig();
    config.setId(id);
    config.setScopeType("GLOBAL");
    config.setSampleRate(sampleRate);
    config.setEnabled(enabled);
    return config;
  }
}
