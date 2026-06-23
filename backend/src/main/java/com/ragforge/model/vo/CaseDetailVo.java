package com.ragforge.model.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class CaseDetailVo {

  private Long judgeResultId;
  private List<Long> kbIds;
  private String query;
  private String answer;
  private List<ChunkSnapshotVo> chunks;
  private Map<String, BigDecimal> scores;
  private String judgeReasoning;
  private List<String> improvements;
  private String bottleneck;
  private LocalDateTime createdAt;

  public Long getJudgeResultId() {
    return judgeResultId;
  }

  public void setJudgeResultId(Long judgeResultId) {
    this.judgeResultId = judgeResultId;
  }

  public List<Long> getKbIds() {
    return kbIds;
  }

  public void setKbIds(List<Long> kbIds) {
    this.kbIds = kbIds;
  }

  public String getQuery() {
    return query;
  }

  public void setQuery(String query) {
    this.query = query;
  }

  public String getAnswer() {
    return answer;
  }

  public void setAnswer(String answer) {
    this.answer = answer;
  }

  public List<ChunkSnapshotVo> getChunks() {
    return chunks;
  }

  public void setChunks(List<ChunkSnapshotVo> chunks) {
    this.chunks = chunks;
  }

  public Map<String, BigDecimal> getScores() {
    return scores;
  }

  public void setScores(Map<String, BigDecimal> scores) {
    this.scores = scores;
  }

  public String getJudgeReasoning() {
    return judgeReasoning;
  }

  public void setJudgeReasoning(String judgeReasoning) {
    this.judgeReasoning = judgeReasoning;
  }

  public List<String> getImprovements() {
    return improvements;
  }

  public void setImprovements(List<String> improvements) {
    this.improvements = improvements;
  }

  public String getBottleneck() {
    return bottleneck;
  }

  public void setBottleneck(String bottleneck) {
    this.bottleneck = bottleneck;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}

