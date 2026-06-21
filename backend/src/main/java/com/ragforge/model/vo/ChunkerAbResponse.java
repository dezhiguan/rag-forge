package com.ragforge.model.vo;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkerAbResponse {

  private List<ResultItem> results;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ResultItem {
    private String strategy;
    private double top1;
    private double mrr;
    private int avgChunkLen;
    private int totalChunks;
  }
}
