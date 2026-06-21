package com.ragforge.controller;

import com.ragforge.common.BizException;
import com.ragforge.common.Result;
import com.ragforge.model.dto.SearchRequest;
import com.ragforge.model.vo.SearchResponse;
import com.ragforge.search.RetrievalService;
import com.ragforge.security.KbAccessGuard;
import com.ragforge.service.RetrievalLogService;
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
  private final RetrievalLogService retrievalLogService;

  @PostMapping("/search")
  @PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR','KB_VIEWER','SERVICE_ACCOUNT')")
  public Result<SearchResponse> search(@Valid @RequestBody SearchRequest req) {
    int queryLen = req.getQuery() == null ? 0 : req.getQuery().length();
    boolean imageOnly = "image".equalsIgnoreCase(req.getModality());
    if (!imageOnly && (req.getQuery() == null || req.getQuery().isBlank())) {
      throw new BizException(400, "QUERY_REQUIRED");
    }
    if (imageOnly && (req.getQueryImageBase64() == null || req.getQueryImageBase64().isBlank())) {
      throw new BizException(400, "QUERY_IMAGE_REQUIRED");
    }
    log.info(
        "ragforge.api search received strategy={} modality={} kbCount={} topK={} queryLen={}",
        req.getStrategy(),
        req.getModality(),
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
            req.getQueryImageBase64(),
            req.getKbIds(),
            req.getDocIds(),
            req.getStrategy(),
            req.getVectorWeight(),
            req.getTopK(),
            req.getRerankTopN(),
            req.getFilter(),
            req.getModality());

    log.info(
        "ragforge.api search completed strategy={} resultCount={} latencyMs={}",
        output.getStrategy(),
        output.getResults() == null ? 0 : output.getResults().size(),
        output.getLatencyMs());
    retrievalLogService.logAsync(
        req.getQuery(),
        output.getStrategy(),
        req.getKbIds(),
        output.getRewrittenQueries(),
        req.getTopK(),
        output.getResults() == null ? 0 : output.getResults().size(),
        output.getLatencyMs(),
        output.getResults());

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
