package com.ragforge.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ragforge.service.JudgeQueryService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class JudgeCostGuardTest {

  @Mock private JudgeQueryService queryService;

  private JudgeCostGuard guard;

  @BeforeEach
  void setUp() {
    JudgeCostGuardProperties properties = new JudgeCostGuardProperties();
    properties.setMonthlyBudgetCny(new BigDecimal("200"));
    properties.setWarnThreshold(new BigDecimal("0.8"));
    properties.setCriticalThreshold(BigDecimal.ONE);
    guard = new JudgeCostGuard(queryService, properties);
  }

  @Test
  void check_warnsWhenMonthlyCostReachesEightyPercent(CapturedOutput output) {
    when(queryService.costThisMonth()).thenReturn(new BigDecimal("160"));

    guard.check();

    assertThat(output).contains("JUDGE_COST_WARN");
  }

  @Test
  void check_errorsWhenMonthlyCostExceedsBudget(CapturedOutput output) {
    when(queryService.costThisMonth()).thenReturn(new BigDecimal("210"));

    guard.check();

    assertThat(output).contains("JUDGE_COST_CRITICAL");
  }

  @Test
  void costGuardBeanNotRegisteredWhenDisabled() {
    new ApplicationContextRunner()
        .withBean(JudgeQueryService.class, () -> org.mockito.Mockito.mock(JudgeQueryService.class))
        .withBean(JudgeCostGuardProperties.class, JudgeCostGuardProperties::new)
        .withUserConfiguration(JudgeCostGuard.class)
        .withPropertyValues("ragforge.judge.cost-guard.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(JudgeCostGuard.class));
  }
}
