package com.ragforge.model.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

public class TrendPointVo {

  private LocalDate date;
  private BigDecimal overall;
  private BigDecimal faithfulness;
  private BigDecimal contextPrecision;
  private BigDecimal answerRelevance;
  private Integer sampleCount;

  public LocalDate getDate() {
    return date;
  }

  public void setDate(LocalDate date) {
    this.date = date;
  }

  public BigDecimal getOverall() {
    return overall;
  }

  public void setOverall(BigDecimal overall) {
    this.overall = overall;
  }

  public BigDecimal getFaithfulness() {
    return faithfulness;
  }

  public void setFaithfulness(BigDecimal faithfulness) {
    this.faithfulness = faithfulness;
  }

  public BigDecimal getContextPrecision() {
    return contextPrecision;
  }

  public void setContextPrecision(BigDecimal contextPrecision) {
    this.contextPrecision = contextPrecision;
  }

  public BigDecimal getAnswerRelevance() {
    return answerRelevance;
  }

  public void setAnswerRelevance(BigDecimal answerRelevance) {
    this.answerRelevance = answerRelevance;
  }

  public Integer getSampleCount() {
    return sampleCount;
  }

  public void setSampleCount(Integer sampleCount) {
    this.sampleCount = sampleCount;
  }
}

