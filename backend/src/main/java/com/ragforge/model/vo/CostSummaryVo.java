package com.ragforge.model.vo;

import java.math.BigDecimal;
import java.util.Map;

public class CostSummaryVo {

  private BigDecimal totalCny;
  private BigDecimal dailyAverageCny;
  private BigDecimal monthlyProjectedCny;
  private Integer totalCalls;
  private Integer failedCalls;
  private Map<String, BigDecimal> costBySource;

  public BigDecimal getTotalCny() {
    return totalCny;
  }

  public void setTotalCny(BigDecimal totalCny) {
    this.totalCny = totalCny;
  }

  public BigDecimal getDailyAverageCny() {
    return dailyAverageCny;
  }

  public void setDailyAverageCny(BigDecimal dailyAverageCny) {
    this.dailyAverageCny = dailyAverageCny;
  }

  public BigDecimal getMonthlyProjectedCny() {
    return monthlyProjectedCny;
  }

  public void setMonthlyProjectedCny(BigDecimal monthlyProjectedCny) {
    this.monthlyProjectedCny = monthlyProjectedCny;
  }

  public Integer getTotalCalls() {
    return totalCalls;
  }

  public void setTotalCalls(Integer totalCalls) {
    this.totalCalls = totalCalls;
  }

  public Integer getFailedCalls() {
    return failedCalls;
  }

  public void setFailedCalls(Integer failedCalls) {
    this.failedCalls = failedCalls;
  }

  public Map<String, BigDecimal> getCostBySource() {
    return costBySource;
  }

  public void setCostBySource(Map<String, BigDecimal> costBySource) {
    this.costBySource = costBySource;
  }
}

