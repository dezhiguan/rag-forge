package com.ragforge.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.Data;

@Data
public class ImageSearchRequest {

  private String query;
  private String queryImageBase64;
  private List<Long> kbIds;
  private List<Long> docIds;

  @Min(value = 1, message = "topK 需在 1~50 之间")
  @Max(value = 50, message = "topK 需在 1~50 之间")
  private int topK = 8;

  @Valid
  private SearchRequest.SearchFilter filter;
}
