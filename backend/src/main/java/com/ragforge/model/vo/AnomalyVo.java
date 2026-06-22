package com.ragforge.model.vo;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class AnomalyVo {

  private String severity;
  private BigDecimal overallDrop;
  private String reason;

  public static AnomalyVo healthy() {
    AnomalyVo vo = new AnomalyVo();
    vo.severity = "NORMAL";
    vo.overallDrop = BigDecimal.ZERO;
    vo.reason = "stable";
    return vo;
  }

  public static AnomalyVo detect(BigDecimal currentOverall, BigDecimal previousOverall, BigDecimal failedRate) {
    AnomalyVo vo = new AnomalyVo();
    vo.overallDrop = BigDecimal.ZERO;
    vo.reason = "stable";
    vo.severity = "NORMAL";

    BigDecimal drop = calculateDrop(currentOverall, previousOverall);
    if (drop.compareTo(BigDecimal.ZERO) > 0) {
      vo.overallDrop = drop;
      vo.reason = "overall_score_dropped_by_" + drop.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%";
    }

    if (drop.compareTo(BigDecimal.valueOf(0.10)) > 0) {
      vo.severity = "CRITICAL";
      return vo;
    }
    if (drop.compareTo(BigDecimal.valueOf(0.05)) > 0) {
      vo.severity = "WARN";
      return vo;
    }

    if (failedRate != null && failedRate.compareTo(BigDecimal.valueOf(0.05)) > 0) {
      vo.severity = "WARN";
      return vo;
    }

    return vo;
  }

  private static BigDecimal calculateDrop(BigDecimal current, BigDecimal previous) {
    if (current == null || previous == null || previous.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal drop = previous.subtract(current).divide(previous, 4, RoundingMode.HALF_UP);
    return drop.max(BigDecimal.ZERO);
  }

  public String getSeverity() {
    return severity;
  }

  public void setSeverity(String severity) {
    this.severity = severity;
  }

  public BigDecimal getOverallDrop() {
    return overallDrop;
  }

  public void setOverallDrop(BigDecimal overallDrop) {
    this.overallDrop = overallDrop;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }
}

