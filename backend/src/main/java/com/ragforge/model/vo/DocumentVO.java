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

  /** 是否压缩包容器（file_type ∈ zip/tar.gz 且无父）。知识库列表层据此显示为压缩包文件（下载原包）。 */
  private Boolean isArchive;

  /** 子文档指向的容器 id（独立文档为 null）。 */
  private Long parentDocumentId;

  /** 子文档在压缩包内的相对路径（独立文档为 null）。 */
  private String archiveEntryPath;

  /** 子文档来源压缩包的文件名，用于文档列表层「来自 xx.zip」标识（独立文档为 null）。 */
  private String sourceArchiveName;

  // 来自关联知识库的冗余字段，方便展示
  private String kbName;
  private String embeddingModel;
  private Integer chunkSize;
  private Integer chunkOverlap;
}
