package com.ragforge.modelcenter;

import com.ragforge.model.entity.ModelConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/** 计价：cost = (inputTokens·inputPrice + outputTokens·outputPrice) / 1e6（元）。本地模型恒为 0。 */
@Component
public class CostCalculator {

  private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);

  public BigDecimal compute(ModelConfig cfg, long inputTokens, long outputTokens) {
    if (cfg == null || Boolean.TRUE.equals(cfg.getIsLocal())) {
      return BigDecimal.ZERO;
    }
    BigDecimal in = nz(cfg.getInputPrice()).multiply(BigDecimal.valueOf(inputTokens));
    BigDecimal out = nz(cfg.getOutputPrice()).multiply(BigDecimal.valueOf(outputTokens));
    return in.add(out).divide(MILLION, 6, RoundingMode.HALF_UP);
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
