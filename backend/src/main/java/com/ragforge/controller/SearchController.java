package com.ragforge.controller;

import com.ragforge.common.Result;
import com.ragforge.model.dto.SearchRequest;
import com.ragforge.model.vo.SearchResponse;
import com.ragforge.search.SearchResult;
import com.ragforge.search.VectorSearchService;
import com.ragforge.service.RetrievalLogService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SearchController {

  private final VectorSearchService vectorSearchService;
  private final RetrievalLogService retrievalLogService;

  @PostMapping("/search")
  public Result<SearchResponse> search(@Valid @RequestBody SearchRequest req) {
    long start = System.currentTimeMillis();
    List<SearchResult> results =
        vectorSearchService.search(req.getQuery(), req.getKbIds(), req.getTopK());
    long latencyMs = System.currentTimeMillis() - start;

    retrievalLogService.logAsync(
        req.getQuery(),
        "vector",
        req.getKbIds(),
        req.getTopK(),
        results.size(),
        latencyMs);

    return Result.ok(new SearchResponse(results, latencyMs, "vector"));
  }
}
