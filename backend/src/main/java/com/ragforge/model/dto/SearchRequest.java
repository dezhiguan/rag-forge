package com.ragforge.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import java.util.List;
import lombok.Data;

@Data
public class SearchRequest {

  private String query;

  private List<Long> kbIds;

  /** 限定文档范围（可选），不传则检索知识库下全部文档 */
  private List<Long> docIds;

  /** vector | keyword | rewrite | hybrid | full */
  private String strategy = "vector";

  /**
   * DEPRECATED in T10-rewrite (2026-06-21). Backend now uses unified vl_vector space and ignores
   * this field.
   */
  @Deprecated
  private String modality = "text";

  /** DEPRECATED in T10-rewrite. Backend ignores this field until the unified image query API lands. */
  @Deprecated
  private String queryImageBase64;

  /** only used by hybrid strategy */
  private Double vectorWeight = 0.55;

  /** used by full strategy rerank */
  @Min(1)
  @Max(50)
  private int rerankTopN = 5;

  @Min(1)
  @Max(50)
  private int topK = 8;

  @Valid
  private SearchFilter filter;

  @Data
  public static class SearchFilter {
    /** chunk_type 取值（OR 关系，多值任意命中即可）；为空或 null 时不过滤 */
    private List<String> chunkType;
  }
}
