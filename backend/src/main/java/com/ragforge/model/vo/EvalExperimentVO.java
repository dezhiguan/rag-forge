package com.ragforge.model.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
public class EvalExperimentVO {
  private Long id;
  private Long datasetId;
  private String datasetName;
  private String strategy;
  private Integer totalQuestions;
  private Integer top1HitCount;
  private Integer top3HitCount;
  private BigDecimal top1HitRate;
  private BigDecimal top3HitRate;
  private BigDecimal mrr;
  private Integer avgLatencyMs;
  private String status;
  private LocalDateTime createdAt;
  private List<EvalFailureSampleVO> failureSamples;
  private List<EvalResultDetailVO> results;
}

