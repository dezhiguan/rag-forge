package com.ragforge.judge;

import java.math.BigDecimal;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ragforge.judge.cost-guard")
public class JudgeCostGuardProperties {

  private BigDecimal monthlyBudgetCny = new BigDecimal("200");
  private BigDecimal warnThreshold = new BigDecimal("0.8");
  private BigDecimal criticalThreshold = BigDecimal.ONE;
}
