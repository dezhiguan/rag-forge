package com.ragforge.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateKbDTO {

  @NotBlank(message = "知识库名称不能为空")
  private String name;

  private String description;

  private Integer chunkSize = 512;

  private Integer chunkOverlap = 64;
}
