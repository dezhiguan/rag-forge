package com.ragforge.model.vo;

import lombok.Data;

@Data
public class DocumentChunkVO {

  private Integer chunkIndex;
  private String content;
  private Integer tokenCount;
  private String chunkerStrategy;
  private String headingPath;
}
