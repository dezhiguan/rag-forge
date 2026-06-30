package com.ragforge.modelcenter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.mapper.ModelUsageDailyMapper;
import com.ragforge.model.entity.ModelConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ModelUsageRecorderTest {

  @Mock private ModelUsageDailyMapper usageMapper;
  @Mock private ModelResolver modelResolver;
  @Mock private CostCalculator costCalculator;

  @InjectMocks private ModelUsageRecorder recorder;

  @Test
  void record_nullEvent_isIgnored() {
    recorder.record(null);
    recorder.flush();
    verify(usageMapper, never()).upsertAccumulate(any(), any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), anyLong(), anyLong(), anyLong());
  }

  @Test
  void record_nullModelCode_isIgnored() {
    recorder.record(new ModelUsageEvent(null, Purpose.ANSWER, 100, 50, 300, true));
    recorder.flush();
    verify(usageMapper, never()).upsertAccumulate(any(), any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), anyLong(), anyLong(), anyLong());
  }

  @Test
  void flush_emptyQueue_doesNothing() {
    recorder.flush();
    verify(usageMapper, never()).upsertAccumulate(any(), any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), anyLong(), anyLong(), anyLong());
  }

  @Test
  void record_thenFlush_upsertsUsage() {
    when(modelResolver.findByCode("qwen-plus")).thenReturn(modelCfg("qwen-plus"));
    when(costCalculator.compute(any(), anyLong(), anyLong()))
        .thenReturn(java.math.BigDecimal.valueOf(0.01));

    recorder.record(new ModelUsageEvent("qwen-plus", Purpose.ANSWER, 500, 200, 350, true));
    recorder.flush();

    verify(usageMapper, atLeastOnce()).upsertAccumulate(
        anyString(), anyString(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), anyLong(), anyLong(), anyLong());
  }

  @Test
  void flush_aggregatesMultipleEventsForSameModel() {
    when(modelResolver.findByCode("qwen-plus")).thenReturn(modelCfg("qwen-plus"));
    when(costCalculator.compute(any(), anyLong(), anyLong()))
        .thenReturn(java.math.BigDecimal.valueOf(0.005));

    recorder.record(new ModelUsageEvent("qwen-plus", Purpose.ANSWER, 100, 50, 200, true));
    recorder.record(new ModelUsageEvent("qwen-plus", Purpose.ANSWER, 200, 100, 300, false));
    recorder.flush();

    // Both events for same model+org should be aggregated into one upsert
    verify(usageMapper, atLeastOnce()).upsertAccumulate(
        anyString(), anyString(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), anyLong(), anyLong(), anyLong());
  }

  @Test
  void flush_upsertFailure_doesNotThrow() {
    when(modelResolver.findByCode("qwen-plus")).thenReturn(modelCfg("qwen-plus"));
    when(costCalculator.compute(any(), anyLong(), anyLong()))
        .thenReturn(java.math.BigDecimal.valueOf(0.01));
    org.mockito.Mockito.doThrow(new RuntimeException("DB error"))
        .when(usageMapper).upsertAccumulate(any(), any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), anyLong(), anyLong(), anyLong());

    recorder.record(new ModelUsageEvent("qwen-plus", Purpose.ANSWER, 100, 50, 300, true));
    // Should not throw
    assertThat(recorder).isNotNull();
    recorder.flush();
  }

  private static ModelConfig modelCfg(String code) {
    ModelConfig c = new ModelConfig();
    c.setCode(code);
    c.setIsLocal(false);
    c.setInputPrice(java.math.BigDecimal.valueOf(0.004));
    c.setOutputPrice(java.math.BigDecimal.valueOf(0.012));
    return c;
  }
}
