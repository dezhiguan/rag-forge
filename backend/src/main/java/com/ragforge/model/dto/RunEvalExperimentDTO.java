package com.ragforge.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RunEvalExperimentDTO {

  @NotNull(message = "数据集不能为空")
  private Long datasetId;

  private String strategy = "full";

  @Min(0)
  @Max(1)
  private Double vectorWeight = 0.55;

  @Min(1)
  @Max(50)
  private Integer topK = 8;
}

