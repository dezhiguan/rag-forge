package com.ragforge.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CreateKbDTO {

  @NotBlank(message = "知识库名称不能为空")
  private String name;

  private String description;

  private Integer chunkSize = 512;

  private Integer chunkOverlap = 64;

  @Pattern(regexp = "OFF|PREVIEW|ON|", message = "answerMode 只能是 OFF / PREVIEW / ON")
  private String answerMode;

  private String answerModel;

  // 多模态开关：创建时默认 OFF，用户在表单里勾选才置 ON。
  @Pattern(regexp = "ON|OFF|", message = "imageProcessingMode 只能是 ON / OFF")
  private String imageProcessingMode;

  // 归属组织 id（为空=个人库）。非空时需当前用户为该组织 OWNER/ADMIN。
  private Long orgId;

  // 可见性：个人库 PRIVATE/PUBLIC；组织库 PRIVATE/ORG。为空默认 PRIVATE。
  @Pattern(regexp = "PRIVATE|ORG|PUBLIC|", message = "visibility 只能是 PRIVATE / ORG / PUBLIC")
  private String visibility;
}
