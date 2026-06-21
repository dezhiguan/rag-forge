package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ragforge.mybatis.handler.JsonbStringTypeHandler;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName(value = "knowledge_bases", autoResultMap = true)
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

  @TableField(value = "chunker_profile_json", typeHandler = JsonbStringTypeHandler.class)
  private String chunkerProfileJson;

  private String imageProcessingMode;
  private String answerMode;
  private String answerModel;
  private Long promptTemplateId;

  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
