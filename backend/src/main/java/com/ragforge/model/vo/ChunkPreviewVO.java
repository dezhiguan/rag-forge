package com.ragforge.model.vo;

import lombok.Data;

@Data
public class ChunkPreviewVO {
  private Long chunkId;
  private Integer chunkIndex;
  private String content;
  private Integer tokenCount;
}
