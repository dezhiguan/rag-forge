package com.ragforge.model.vo;

import com.ragforge.search.SearchResult;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchResponse {

  private List<SearchResult> results;
  private long latencyMs;
  private String strategy;
  private List<String> rewrittenQueries;
  private Long rewriteLatencyMs;
  private Long vectorLatencyMs;
  private Long keywordLatencyMs;
  private Long rerankLatencyMs;
}
