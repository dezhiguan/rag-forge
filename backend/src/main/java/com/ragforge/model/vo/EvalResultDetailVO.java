package com.ragforge.model.vo;

import java.util.List;
import lombok.Data;

@Data
public class EvalResultDetailVO {
  private Long questionId;
  private String question;
  private List<Long> expectedChunkIds;
  private List<Long> recalledChunkIds;
  private Integer hitAt;
  private boolean top1Hit;
  private boolean top3Hit;
  private Double mrr;
  private Integer latencyMs;
  private String failureReason;
}

