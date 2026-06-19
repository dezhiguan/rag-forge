package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("knowledge_bases")
public class KnowledgeBase {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String name;
  private String description;
  private String embeddingModel;
  private Integer chunkSize;
  private Integer chunkOverlap;
  private Integer docCount;
  private Integer chunkCount;
  private String status;
  private String tenantId;
  private Long ownerUserId;
  private String visibility;
  private String kbType;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
