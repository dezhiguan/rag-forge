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
  private String chunkModality;
  private String imageKey;
  /** IMAGE chunk 的预签名图片 URL（仅 controller 出参时回填，供前端缩略图预览）。 */
  private String imageUrl;
}
