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

  @Min(1)
  @Max(50)
  private int topK = 8;

  @Valid
  private SearchRequest.SearchFilter filter;
}
