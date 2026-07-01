package com.ragforge.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 修改知识库可见性请求。 */
@Data
public class ChangeVisibilityDTO {

  /** 目标可见性：私有 PRIVATE / 组织内公开 ORG（已移除全域公开 PUBLIC）。 */
  @NotBlank private String visibility;

  /** 变更原因（用于审计；收紧/放开可见性时建议必填）。 */
  private String reason;

  /** 存在依赖时是否强制执行（前端确认影响清单后置 true）。 */
  private boolean force;
}
