package com.ragforge.model.vo;

import java.math.BigDecimal;

public class SampleStatsVo {

  private Integer totalSamples;
  private Integer failedSamples;
  private BigDecimal failedRate;

  public Integer getTotalSamples() {
    return totalSamples;
  }

  public void setTotalSamples(Integer totalSamples) {
    this.totalSamples = totalSamples;
  }

  public Integer getFailedSamples() {
    return failedSamples;
  }

  public void setFailedSamples(Integer failedSamples) {
    this.failedSamples = failedSamples;
  }

  public BigDecimal getFailedRate() {
    return failedRate;
  }

  public void setFailedRate(BigDecimal failedRate) {
    this.failedRate = failedRate;
  }
}

