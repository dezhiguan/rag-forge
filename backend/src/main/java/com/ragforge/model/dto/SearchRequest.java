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

  @Min(1)
  @Max(50)
  private int topK = 8;
}
