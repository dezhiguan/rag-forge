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
    // 该策略未产出分块时的说明(如语义分块要求文档≥2000字被跳过),供前端区分"跳过"与"命中率0"。
    private String note;
  }
}
