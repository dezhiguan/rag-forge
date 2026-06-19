package com.ragforge.controller;

import com.ragforge.common.BizException;
import com.ragforge.common.Result;
import com.ragforge.model.dto.SearchRequest;
import com.ragforge.model.vo.SearchResponse;
import com.ragforge.search.RetrievalService;
import com.ragforge.security.KbAccessGuard;
import com.ragforge.search.RetrievalService.RetrievalOutput;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
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
  private final KbAccessGuard kbAccessGuard;

  @PostMapping("/search")
  @PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR','KB_VIEWER','SERVICE_ACCOUNT')")
  public Result<SearchResponse> search(@Valid @RequestBody SearchRequest req) {
    int queryLen = req.getQuery() == null ? 0 : req.getQuery().length();
    log.info(
        "ragforge.api search received strategy={} kbCount={} topK={} queryLen={}",
        req.getStrategy(),
        req.getKbIds() == null ? 0 : req.getKbIds().size(),
        req.getTopK(),
        queryLen);

    boolean requestedSpecificKbs = req.getKbIds() != null && !req.getKbIds().isEmpty();
    Set<Long> readableKbIds =
        requestedSpecificKbs ? kbAccessGuard.filterReadable(req.getKbIds()) : kbAccessGuard.allReadableKbIds();
    if (readableKbIds.isEmpty()) {
      throw new BizException(403, "KB_ACCESS_DENIED");
    }
    req.setKbIds(new ArrayList<>(readableKbIds));

    RetrievalOutput output =
        retrievalService.retrieve(
            req.getQuery(),
            req.getKbIds(),
            req.getDocIds(),
            req.getStrategy(),
            req.getVectorWeight(),
            req.getTopK(),
            req.getRerankTopN(),
            req.getFilter());

    log.info(
        "ragforge.api search completed strategy={} resultCount={} latencyMs={}",
        output.getStrategy(),
        output.getResults() == null ? 0 : output.getResults().size(),
        output.getLatencyMs());

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
