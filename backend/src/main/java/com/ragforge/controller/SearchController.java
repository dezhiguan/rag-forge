package com.ragforge.controller;

import com.ragforge.common.Result;
import com.ragforge.model.dto.SearchRequest;
import com.ragforge.model.vo.SearchResponse;
import com.ragforge.search.RetrievalService;
import com.ragforge.search.RetrievalService.RetrievalOutput;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SearchController {

  private final RetrievalService retrievalService;

  @PostMapping("/search")
  public Result<SearchResponse> search(@Valid @RequestBody SearchRequest req) {
    RetrievalOutput output =
        retrievalService.retrieve(
            req.getQuery(),
            req.getKbIds(),
            req.getDocIds(),
            req.getStrategy(),
            req.getVectorWeight(),
            req.getTopK(),
            req.getRerankTopN());

    return Result.ok(
        new SearchResponse(
            output.getResults(),
            output.getLatencyMs(),
            output.getStrategy(),
            output.getRewrittenQueries(),
            output.getRewriteLatencyMs(),
            output.getVectorLatencyMs(),
            output.getKeywordLatencyMs(),
            output.getRerankLatencyMs()));
  }
}
