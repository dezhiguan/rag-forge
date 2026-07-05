package com.ragforge.model.vo;

import java.util.List;
import lombok.Data;

@Data
public class EvalQuestionVO {

  private Long id;
  private Long datasetId;
  private String question;
  private List<Long> expectedChunkIds;
  private List<String> expectedTextSnippets;
  private Boolean judgeEnabled;
  private List<String> judgeTags;

  /** 核心题（平台级黄金集冻结基线）：前端据此显示 🔒 并禁用编辑/删除。 */
  private Boolean isCore;
}
