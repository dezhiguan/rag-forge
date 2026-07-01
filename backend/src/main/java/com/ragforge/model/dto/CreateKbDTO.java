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

  // 可见性：个人库仅「私有」；团队库「私有 / 组织内公开」。为空默认「私有」。
  // 合法性交由 service 层（applyOwnership）按组织类型权威校验，返回带机器码的友好中文提示，
  // 不在此用 @Pattern 暴露 PRIVATE/ORG/PUBLIC 等技术枚举值。
  private String visibility;
}
