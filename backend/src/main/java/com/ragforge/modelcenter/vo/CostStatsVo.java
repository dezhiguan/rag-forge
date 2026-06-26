package com.ragforge.modelcenter.vo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 成本看板：顶部 KPI + 趋势 + 费用排行。 */
public record CostStatsVo(Kpi kpi, List<TrendPoint> trend, List<RankItem> ranking) {

  public record Kpi(
      int modelCount,
      int enabledCount,
      BigDecimal monthlyCost,
      long monthlyInputTokens,
      long monthlyOutputTokens,
      long callCount,
      double successRate) {}

  /** 某一天按用途堆叠的费用。byPurpose key = Purpose 名称，value = 当日费用（元）。 */
  public record TrendPoint(String date, Map<String, BigDecimal> byPurpose) {}

  public record RankItem(String modelCode, BigDecimal cost, double pct) {}
}
