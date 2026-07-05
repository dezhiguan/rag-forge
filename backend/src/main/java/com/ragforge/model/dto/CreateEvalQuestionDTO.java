package com.ragforge.model.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.Data;

@Data
public class CreateEvalQuestionDTO {

  @NotBlank(message = "问题不能为空")
  private String question;

  private List<Long> expectedChunkIds;
  private List<String> expectedTextSnippets;
  private Boolean judgeEnabled = Boolean.FALSE;
  private List<String> judgeTags;

  /** 核心题标记；仅平台超管可置 TRUE（服务端二次校验），用于内置平台级黄金集冻结基线。 */
  private Boolean isCore = Boolean.FALSE;
}
