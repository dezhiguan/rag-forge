package com.ragforge.model.vo;

import com.ragforge.model.entity.EvalDataset;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class EvalDatasetVO {

  private Long id;
  private String name;
  private Long kbId;
  private Integer questionCount;
  private LocalDateTime createdAt;

  public static EvalDatasetVO fromEntity(EvalDataset entity) {
    EvalDatasetVO vo = new EvalDatasetVO();
    vo.setId(entity.getId());
    vo.setName(entity.getName());
    vo.setKbId(entity.getKbId());
    vo.setQuestionCount(entity.getQuestionCount());
    vo.setCreatedAt(entity.getCreatedAt());
    return vo;
  }
}
