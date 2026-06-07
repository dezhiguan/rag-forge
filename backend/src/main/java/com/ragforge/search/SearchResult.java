package com.ragforge.search;

import lombok.Data;

@Data
public class SearchResult {

  private Long chunkId;
  private Long docId;
  private String filename;
  private String content;
  private int chunkIndex;
  private double vectorScore;
  private double bm25Score;
  private double finalScore;
  private String chunkType;
}
