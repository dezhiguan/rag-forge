package com.ragforge.model.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UpdateKbDTO {

  private String name;
  private String description;
  private Integer chunkSize;
  private Integer chunkOverlap;
  private String status;
  @Pattern(regexp = "OFF|PREVIEW|ON|", message = "answerMode 只能是 OFF / PREVIEW / ON")
  private String answerMode;
  private String answerModel;
}
