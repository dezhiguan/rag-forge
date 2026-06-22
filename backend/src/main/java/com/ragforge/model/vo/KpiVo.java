package com.ragforge.model.vo;

import java.math.BigDecimal;

public class KpiVo {

  private BigDecimal overallScore;
  private BigDecimal overallChange;
  private BigDecimal faithfulness;
  private BigDecimal contextPrecision;
  private BigDecimal answerRelevance;
  private Integer retrievalLatencyP95Ms;
  private BigDecimal costLastPeriodCny;

  public BigDecimal getOverallScore() {
    return overallScore;
  }

  public void setOverallScore(BigDecimal overallScore) {
    this.overallScore = overallScore;
  }

  public BigDecimal getOverallChange() {
    return overallChange;
  }

  public void setOverallChange(BigDecimal overallChange) {
    this.overallChange = overallChange;
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

  public Integer getRetrievalLatencyP95Ms() {
    return retrievalLatencyP95Ms;
  }

  public void setRetrievalLatencyP95Ms(Integer retrievalLatencyP95Ms) {
    this.retrievalLatencyP95Ms = retrievalLatencyP95Ms;
  }

  public BigDecimal getCostLastPeriodCny() {
    return costLastPeriodCny;
  }

  public void setCostLastPeriodCny(BigDecimal costLastPeriodCny) {
    this.costLastPeriodCny = costLastPeriodCny;
  }
}

