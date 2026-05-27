package com.ragforge.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateEvalDatasetDTO {

  @NotBlank(message = "数据集名称不能为空")
  private String name;

  @NotNull(message = "知识库不能为空")
  private Long kbId;
}
