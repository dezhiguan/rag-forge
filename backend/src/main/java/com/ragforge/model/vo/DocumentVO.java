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
  private String fileMd5;
  private Integer version;
  private String parseStatus;
  private Integer chunkCount;
  private String errorMsg;
  private LocalDateTime createdAt;

  // 来自关联知识库的冗余字段，方便展示
  private String kbName;
  private String embeddingModel;
  private Integer chunkSize;
  private Integer chunkOverlap;
}

