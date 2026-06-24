package com.ragforge.model.dto;

import lombok.Data;

@Data
public class RechunkRequest {
  /** MARKDOWN_HEADING / FIXED_WINDOW / RECURSIVE / SEMANTIC / TABLE_AWARE; null uses KB default. */
  private String strategy;

  /** Only for FIXED_WINDOW / RECURSIVE. */
  private Integer chunkSize;

  /** Only for FIXED_WINDOW / RECURSIVE. */
  private Integer chunkOverlap;
}
