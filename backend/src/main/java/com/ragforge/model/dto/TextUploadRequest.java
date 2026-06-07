package com.ragforge.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TextUploadRequest {
  @NotNull private Long kbId;

  @NotBlank private String title;

  @NotBlank private String content;

  /** 可选；非空时写入 document_chunks.chunk_type，如 "RESUME" */
  private String chunkType;
}
