package com.ragforge.judge;

import com.ragforge.service.JudgeQueryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ragforge.judge.cost-guard.enabled", havingValue = "true", matchIfMissing = true)
public class JudgeCostGuard {

  private final JudgeQueryService queryService;
  private final JudgeCostGuardProperties properties;

  @Scheduled(cron = "0 0 * * * *")
  public void check() {
    BigDecimal currentMonth = safeMoney(queryService.costThisMonth());
    BigDecimal budget = safeMoney(properties.getMonthlyBudgetCny());
    if (budget.compareTo(BigDecimal.ZERO) <= 0) {
      log.warn("JUDGE_COST_GUARD_DISABLED: monthly budget is {}", budget);
      return;
    }

    BigDecimal ratio = currentMonth.divide(budget, 4, RoundingMode.HALF_UP);
    if (ratio.compareTo(properties.getCriticalThreshold()) >= 0) {
      log.error("JUDGE_COST_CRITICAL: 本月成本 {} CNY 已超预算 {} CNY", currentMonth, budget);
    } else if (ratio.compareTo(properties.getWarnThreshold()) >= 0) {
      log.warn(
          "JUDGE_COST_WARN: 本月成本 {} CNY 已用预算 {}%",
          currentMonth,
          ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP));
    }
  }

  private static BigDecimal safeMoney(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
