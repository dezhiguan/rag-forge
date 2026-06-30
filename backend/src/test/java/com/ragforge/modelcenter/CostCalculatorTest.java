package com.ragforge.modelcenter;

import static org.assertj.core.api.Assertions.assertThat;

import com.ragforge.model.entity.ModelConfig;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CostCalculatorTest {

  private final CostCalculator calc = new CostCalculator();

  @Test
  void compute_remoteModel_calculatesCorrectCost() {
    ModelConfig cfg = modelCfg("qwen-plus", false, "0.004", "0.012");
    // 500 input + 200 output: (500*0.004 + 200*0.012) / 1e6 = 0.0000044, rounded to 6dp.
    BigDecimal cost = calc.compute(cfg, 500, 200);
    assertThat(cost).isEqualByComparingTo("0.000004");
  }

  @Test
  void compute_localModel_returnsZero() {
    ModelConfig cfg = modelCfg("local-bge", true, "0.004", "0.012");
    assertThat(calc.compute(cfg, 1000, 500)).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void compute_nullConfig_returnsZero() {
    assertThat(calc.compute(null, 100, 100)).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void compute_nullPrices_treatsAsZero() {
    ModelConfig cfg = new ModelConfig();
    cfg.setCode("model-x");
    cfg.setIsLocal(false);
    // inputPrice and outputPrice are null
    assertThat(calc.compute(cfg, 1000, 1000)).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void compute_zeroTokens_returnsZero() {
    ModelConfig cfg = modelCfg("qwen-turbo", false, "0.002", "0.006");
    assertThat(calc.compute(cfg, 0, 0)).isEqualByComparingTo(BigDecimal.ZERO);
  }

  private static ModelConfig modelCfg(String code, boolean local, String inPrice, String outPrice) {
    ModelConfig cfg = new ModelConfig();
    cfg.setCode(code);
    cfg.setIsLocal(local);
    cfg.setInputPrice(new BigDecimal(inPrice));
    cfg.setOutputPrice(new BigDecimal(outPrice));
    return cfg;
  }
}
