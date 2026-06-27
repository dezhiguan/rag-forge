package com.ragforge.model.vo;

import com.ragforge.model.entity.KnowledgeBase;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class KnowledgeBaseVO {

  private Long id;
  private String name;
  private String description;
  private String embeddingModel;
  private Integer chunkSize;
  private Integer chunkOverlap;
  private Integer docCount;
  private Integer chunkCount;
  private String status;
  private String answerMode;
  private String answerModel;
  private String imageProcessingMode;
  private Long ownerUserId;
  private String visibility;
  private String kbType;
  /** 当前主体对该库的权限：admin | write | read。 */
  private String myPermission;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public static KnowledgeBaseVO fromEntity(KnowledgeBase entity) {
    KnowledgeBaseVO vo = new KnowledgeBaseVO();
    vo.setId(entity.getId());
    vo.setName(entity.getName());
    vo.setDescription(entity.getDescription());
    vo.setEmbeddingModel(entity.getEmbeddingModel());
    vo.setChunkSize(entity.getChunkSize());
    vo.setChunkOverlap(entity.getChunkOverlap());
    vo.setDocCount(entity.getDocCount());
    vo.setChunkCount(entity.getChunkCount());
    vo.setStatus(entity.getStatus());
    vo.setAnswerMode(entity.getAnswerMode());
    vo.setAnswerModel(entity.getAnswerModel());
    vo.setImageProcessingMode(entity.getImageProcessingMode());
    vo.setOwnerUserId(entity.getOwnerUserId());
    vo.setVisibility(entity.getVisibility());
    vo.setKbType(entity.getKbType());
    vo.setCreatedAt(entity.getCreatedAt());
    vo.setUpdatedAt(entity.getUpdatedAt());
    return vo;
  }
}
