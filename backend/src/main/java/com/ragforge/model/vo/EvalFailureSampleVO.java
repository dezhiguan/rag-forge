package com.ragforge.model.vo;

import java.util.List;
import lombok.Data;

@Data
public class EvalFailureSampleVO {
  private Long questionId;
  private String question;
  private String failureReason;
  private String suggestion;
  private List<Long> expectedChunkIds;
  private List<Long> recalledChunkIds;
  private List<ChunkPreviewVO> expectedChunks;
  private List<ChunkPreviewVO> recalledChunks;
  private Integer expectedBestRank;
  private Boolean expectedInTopK;
  private Boolean expectedInTop3;
  private Integer recalledCount;
}
