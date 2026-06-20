package com.ragforge.model.vo;

import java.util.List;
import lombok.Data;

@Data
public class EvalResultDetailVO {
  private Long questionId;
  private String question;
  private List<Long> expectedChunkIds;
  private List<String> expectedTextSnippets;
  private List<Long> recalledChunkIds;
  private List<ChunkPreviewVO> expectedChunks;
  private List<ChunkPreviewVO> recalledChunks;
  private List<EvalTextSnippetMatchVO> expectedTextMatches;
  private Integer hitAt;
  private boolean top1Hit;
  private boolean top3Hit;
  private Double mrr;
  private Integer latencyMs;
  private String failureReason;
  private String suggestion;
  private Integer expectedBestRank;
  private Boolean expectedInTopK;
  private Boolean expectedInTop3;
  private Integer recalledCount;
}
