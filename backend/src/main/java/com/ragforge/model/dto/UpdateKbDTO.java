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

  // 多模态开关：ON=抽取并入库嵌入图（HTML/PDF/Word 内嵌图、纯图片文档），OFF=只走文本管道。
  // 空字符串表示"不动当前值"，让 PUT 调用方可以局部更新。
  @Pattern(regexp = "ON|OFF|", message = "imageProcessingMode 只能是 ON / OFF")
  private String imageProcessingMode;
}
