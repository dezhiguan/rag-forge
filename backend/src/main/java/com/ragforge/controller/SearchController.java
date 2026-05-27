package com.ragforge.controller;

import com.ragforge.common.Result;
import com.ragforge.model.dto.SearchRequest;
import com.ragforge.model.vo.SearchResponse;
import com.ragforge.search.EsSearchService;
import com.ragforge.search.QueryRewriter;
import com.ragforge.search.SearchResult;
import com.ragforge.search.VectorSearchService;
import com.ragforge.service.RetrievalLogService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  private final EsSearchService esSearchService;
  private final QueryRewriter queryRewriter;
  private final RetrievalLogService retrievalLogService;

  @PostMapping("/search")
  public Result<SearchResponse> search(@Valid @RequestBody SearchRequest req) {
    String strategy = normalizeStrategy(req.getStrategy());
    long start = System.currentTimeMillis();

    List<String> rewrittenQueries = null;
    List<SearchResult> results;
    if ("rewrite".equals(strategy)) {
      rewrittenQueries = queryRewriter.rewrite(req.getQuery());
      results = searchByRewrittenQueries(rewrittenQueries, req.getKbIds(), req.getTopK());
    } else if ("keyword".equals(strategy)) {
      results = esSearchService.search(req.getQuery(), req.getKbIds(), req.getTopK());
    } else {
      results = vectorSearchService.search(req.getQuery(), req.getKbIds(), req.getTopK());
    }

    long latencyMs = System.currentTimeMillis() - start;

    retrievalLogService.logAsync(
        req.getQuery(),
        strategy,
        req.getKbIds(),
        rewrittenQueries,
        req.getTopK(),
        results.size(),
        latencyMs);

    return Result.ok(new SearchResponse(results, latencyMs, strategy, rewrittenQueries));
  }

  private static String normalizeStrategy(String strategy) {
    if ("rewrite".equalsIgnoreCase(strategy)) {
      return "rewrite";
    }
    if ("keyword".equalsIgnoreCase(strategy)) {
      return "keyword";
    }
    return "vector";
  }

  private List<SearchResult> searchByRewrittenQueries(
      List<String> queries, List<Long> kbIds, int topK) {
    Map<Long, SearchResult> dedup = new LinkedHashMap<>();
    for (String q : queries) {
      List<SearchResult> partial = vectorSearchService.search(q, kbIds, topK);
      for (SearchResult item : partial) {
        if (item.getChunkId() == null) {
          continue;
        }
        SearchResult existing = dedup.get(item.getChunkId());
        if (existing == null || item.getVectorScore() > existing.getVectorScore()) {
          dedup.put(item.getChunkId(), item);
        }
      }
    }
    List<SearchResult> merged = new ArrayList<>(dedup.values());
    merged.sort(Comparator.comparingDouble(SearchResult::getVectorScore).reversed());
    if (merged.size() > topK) {
      return merged.subList(0, topK);
    }
    return merged;
  }
}
