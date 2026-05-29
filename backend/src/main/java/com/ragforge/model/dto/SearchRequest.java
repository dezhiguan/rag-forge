package com.ragforge.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.Data;

@Data
public class SearchRequest {

  @NotBlank(message = "查询内容不能为空")
  private String query;

  private List<Long> kbIds;

  /** 限定文档范围（可选），不传则检索知识库下全部文档 */
  private List<Long> docIds;

  /** vector | keyword | rewrite | hybrid | full */
  private String strategy = "vector";

  /** only used by hybrid strategy */
  private Double vectorWeight = 0.55;

  /** used by full strategy rerank */
  @Min(1)
  @Max(50)
  private int rerankTopN = 5;

  @Min(1)
  @Max(50)
  private int topK = 8;
}
