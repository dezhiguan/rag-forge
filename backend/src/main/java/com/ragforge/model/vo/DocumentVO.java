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
  private String externalId;
  private String sourceUrl;
  private String contentMd5;
  private String ingestSource;
  private LocalDateTime createdAt;

  /** 是否压缩包容器（file_type ∈ zip/tar.gz 且无父）。前端据此把该行渲染为可展开组。 */
  private Boolean isArchive;

  // 来自关联知识库的冗余字段，方便展示
  private String kbName;
  private String embeddingModel;
  private Integer chunkSize;
  private Integer chunkOverlap;
}
