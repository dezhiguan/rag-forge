package com.ragforge.model.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class WorstCaseVo {

  private Long judgeResultId;
  private Long answerLogId;
  private String query;
  private BigDecimal overallScore;
  private LocalDateTime createdAt;
  private String topIssue;

  public Long getJudgeResultId() {
    return judgeResultId;
  }

  public void setJudgeResultId(Long judgeResultId) {
    this.judgeResultId = judgeResultId;
  }

  public Long getAnswerLogId() {
    return answerLogId;
  }

  public void setAnswerLogId(Long answerLogId) {
    this.answerLogId = answerLogId;
  }

  public String getQuery() {
    return query;
  }

  public void setQuery(String query) {
    this.query = query;
  }

  public BigDecimal getOverallScore() {
    return overallScore;
  }

  public void setOverallScore(BigDecimal overallScore) {
    this.overallScore = overallScore;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public String getTopIssue() {
    return topIssue;
  }

  public void setTopIssue(String topIssue) {
    this.topIssue = topIssue;
  }
}

