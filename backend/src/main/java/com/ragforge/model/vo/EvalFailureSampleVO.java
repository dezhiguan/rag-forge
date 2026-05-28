package com.ragforge.model.vo;

import java.util.List;
import lombok.Data;

@Data
public class EvalFailureSampleVO {
  private Long questionId;
  private String question;
  private String failureReason;
  private List<Long> expectedChunkIds;
  private List<Long> recalledChunkIds;
}

