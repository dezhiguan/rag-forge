package com.ragforge.model.vo;

import java.math.BigDecimal;

public class KpiVo {

  private BigDecimal overallScore;
  private BigDecimal overallChange;
  private BigDecimal faithfulness;
  private BigDecimal faithfulnessChange;
  private BigDecimal contextPrecision;
  private BigDecimal contextPrecisionChange;
  private BigDecimal answerRelevance;
  private BigDecimal answerRelevanceChange;
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

  public BigDecimal getFaithfulnessChange() {
    return faithfulnessChange;
  }

  public void setFaithfulnessChange(BigDecimal faithfulnessChange) {
    this.faithfulnessChange = faithfulnessChange;
  }

  public BigDecimal getContextPrecision() {
    return contextPrecision;
  }

  public void setContextPrecision(BigDecimal contextPrecision) {
    this.contextPrecision = contextPrecision;
  }

  public BigDecimal getContextPrecisionChange() {
    return contextPrecisionChange;
  }

  public void setContextPrecisionChange(BigDecimal contextPrecisionChange) {
    this.contextPrecisionChange = contextPrecisionChange;
  }

  public BigDecimal getAnswerRelevance() {
    return answerRelevance;
  }

  public void setAnswerRelevance(BigDecimal answerRelevance) {
    this.answerRelevance = answerRelevance;
  }

  public BigDecimal getAnswerRelevanceChange() {
    return answerRelevanceChange;
  }

  public void setAnswerRelevanceChange(BigDecimal answerRelevanceChange) {
    this.answerRelevanceChange = answerRelevanceChange;
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

