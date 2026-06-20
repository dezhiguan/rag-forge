package com.ragforge.model.dto;

import lombok.Data;

@Data
public class PresignUploadRequest {
  private Long kbId;
  private String filename;
  private String contentType;
  private Long declaredSize;
}
