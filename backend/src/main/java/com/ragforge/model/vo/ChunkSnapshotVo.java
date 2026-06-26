package com.ragforge.model.vo;

public class ChunkSnapshotVo {

  /** 引用序号，与生成答案中的 [N] 角标对应；可能为空。 */
  private Integer index;
  private Long chunkId;
  private Long docId;
  private String snippet;
  private String content;
  private java.math.BigDecimal score;
  private Boolean relevant;
  private String imageUrl;

  public Integer getIndex() {
    return index;
  }

  public void setIndex(Integer index) {
    this.index = index;
  }

  public Long getChunkId() {
    return chunkId;
  }

  public void setChunkId(Long chunkId) {
    this.chunkId = chunkId;
  }

  public Long getDocId() {
    return docId;
  }

  public void setDocId(Long docId) {
    this.docId = docId;
  }

  public String getSnippet() {
    return snippet;
  }

  public void setSnippet(String snippet) {
    this.snippet = snippet;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public java.math.BigDecimal getScore() {
    return score;
  }

  public void setScore(java.math.BigDecimal score) {
    this.score = score;
  }

  public Boolean getRelevant() {
    return relevant;
  }

  public void setRelevant(Boolean relevant) {
    this.relevant = relevant;
  }

  public String getImageUrl() {
    return imageUrl;
  }

  public void setImageUrl(String imageUrl) {
    this.imageUrl = imageUrl;
  }
}

