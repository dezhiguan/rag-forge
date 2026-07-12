package com.ragforge.model.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UpdateKbDTO {

  private String name;
  private String description;

  @Min(value = 64, message = "分块大小需在 64~8192 之间")
  @Max(value = 8192, message = "分块大小需在 64~8192 之间")
  private Integer chunkSize;

  @Min(value = 0, message = "分块重叠需在 0~4096 之间")
  @Max(value = 4096, message = "分块重叠需在 0~4096 之间")
  private Integer chunkOverlap;

  private String status;
  @Pattern(regexp = "OFF|PREVIEW|ON|", message = "answerMode 只能是 OFF / PREVIEW / ON")
  private String answerMode;
  private String answerModel;

  // 多模态开关：ON=抽取并入库嵌入图（HTML/PDF/Word 内嵌图、纯图片文档），OFF=只走文本管道。
  // 空字符串表示"不动当前值"，让 PUT 调用方可以局部更新。
  @Pattern(regexp = "ON|OFF|", message = "imageProcessingMode 只能是 ON / OFF")
  private String imageProcessingMode;

  /** 局部更新场景：仅当本次同时传了两者时才校验重叠<大小，避免误伤只改其一的请求。 */
  @AssertTrue(message = "分块重叠必须小于分块大小")
  public boolean isChunkOverlapValid() {
    if (chunkSize == null || chunkOverlap == null) {
      return true;
    }
    return chunkOverlap < chunkSize;
  }
}
