package com.ragforge.model.dto;

import java.util.Map;
import lombok.Data;

@Data
public class RegisterUploadRequest {
  private String uploadToken;
  private Long kbId;
  private Identity identity;
  private OnConflict onConflict = OnConflict.REJECT;
  private String ingestSource;
  private String chunkType;
  private Map<String, Object> metadata;
}
