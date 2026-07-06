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
  /** 冻结基线：含核心题(is_core)的数据集不可删除，前端据此置灰操作按钮。 */
  private Boolean locked;

  public static EvalDatasetVO fromEntity(EvalDataset entity) {
    EvalDatasetVO vo = new EvalDatasetVO();
    vo.setId(entity.getId());
    vo.setName(entity.getName());
    vo.setKbId(entity.getKbId());
    vo.setQuestionCount(entity.getQuestionCount());
    vo.setCreatedAt(entity.getCreatedAt());
    vo.setLocked(Boolean.FALSE);
    return vo;
  }
}
