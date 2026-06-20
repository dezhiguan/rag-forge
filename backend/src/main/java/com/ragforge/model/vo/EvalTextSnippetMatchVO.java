package com.ragforge.model.vo;

import lombok.Data;

@Data
public class EvalTextSnippetMatchVO {
  private String textSnippet;
  private Boolean matched;
  private Long matchedChunkId;
  private Long matchedDocId;
  private String matchedContent;
}
