package com.ragforge.model.vo;

import java.util.List;
import lombok.Data;

/** 可见性变更影响评估（P1：收紧前预检跨组织依赖）。 */
@Data
public class VisibilityImpactVo {

  /** 当前可见性。 */
  private String current;

  /** 目标可见性。 */
  private String target;

  /** 是否为收紧（target 比 current 更不开放）。 */
  private boolean narrowing;

  /** 是否放开到全平台（target=PUBLIC），用于前端强确认（P2）。 */
  private boolean willOpenToPlatform;

  /** 收紧后会失去访问的「他组织」API key（直接断链，最高风险）。 */
  private List<KeyRef> crossOrgApiKeys;

  /** 建立在本库上的评测数据集数量（信息提示，收紧后仅本组织管理员可用）。 */
  private int evalDatasetCount;

  /** 是否存在阻断性依赖（crossOrgApiKeys 非空）；前端据此要求强确认 force。 */
  private boolean hasBlockingDependencies;

  @Data
  public static class KeyRef {
    private Long id;
    private String keyName;
    private Long orgId;
    private String orgName;
  }
}
