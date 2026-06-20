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
}
