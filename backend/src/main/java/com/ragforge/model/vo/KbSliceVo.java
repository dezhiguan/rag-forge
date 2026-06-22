package com.ragforge.model.vo;

import java.math.BigDecimal;

public class KbSliceVo {

  private Long kbId;
  private String kbName;
  private BigDecimal overallScore;
  private BigDecimal trend;
  private Integer sampleCount;

  public Long getKbId() {
    return kbId;
  }

  public void setKbId(Long kbId) {
    this.kbId = kbId;
  }

  public String getKbName() {
    return kbName;
  }

  public void setKbName(String kbName) {
    this.kbName = kbName;
  }

  public BigDecimal getOverallScore() {
    return overallScore;
  }

  public void setOverallScore(BigDecimal overallScore) {
    this.overallScore = overallScore;
  }

  public BigDecimal getTrend() {
    return trend;
  }

  public void setTrend(BigDecimal trend) {
    this.trend = trend;
  }

  public Integer getSampleCount() {
    return sampleCount;
  }

  public void setSampleCount(Integer sampleCount) {
    this.sampleCount = sampleCount;
  }
}

