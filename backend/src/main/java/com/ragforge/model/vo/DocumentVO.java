package com.ragforge.model.vo;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class DocumentVO {

  private Long id;
  private Long kbId;
  private String filename;
  private Long fileSize;
  private String fileType;
  private String parseStatus;
  private Integer chunkCount;
  private LocalDateTime createdAt;
}

