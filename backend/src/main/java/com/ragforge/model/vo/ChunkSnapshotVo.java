package com.ragforge.model.vo;

public class ChunkSnapshotVo {

  private Long chunkId;
  private Long docId;
  private String snippet;
  private String content;

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
}

