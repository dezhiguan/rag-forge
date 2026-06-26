package com.ragforge.controller;

import com.ragforge.common.BizException;
import com.ragforge.common.Result;
import com.ragforge.model.dto.SearchRequest;
import com.ragforge.model.dto.ImageSearchRequest;
import com.ragforge.model.vo.SearchResponse;
import com.ragforge.search.RetrievalService;
import com.ragforge.security.KbAccessGuard;
import com.ragforge.service.RetrievalLogService;
import com.ragforge.search.RetrievalService.RetrievalOutput;
import com.ragforge.search.SearchResult;
import com.ragforge.search.VectorSearchService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
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
  private final VectorSearchService vectorSearchService;
  private final KbAccessGuard kbAccessGuard;
  private final RetrievalLogService retrievalLogService;
  private final com.ragforge.storage.ChunkImageResolver chunkImageResolver;

  /** 为 IMAGE chunk 回填预签名缩略图 URL，供前端检索调试台预览。 */
  private void enrichImageUrls(java.util.List<SearchResult> results) {
    if (results == null || results.isEmpty()) {
      return;
    }
    java.util.List<Long> imageChunkIds =
        results.stream()
            .filter(r -> "IMAGE".equalsIgnoreCase(r.getChunkModality()) && r.getChunkId() != null)
            .map(SearchResult::getChunkId)
            .toList();
    if (imageChunkIds.isEmpty()) {
      return;
    }
    java.util.Map<Long, String> urls = chunkImageResolver.presignedUrls(imageChunkIds);
    results.forEach(r -> r.setImageUrl(urls.get(r.getChunkId())));
  }

  @PostMapping("/search")
  @PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR','KB_VIEWER','SERVICE_ACCOUNT')")
  public Result<SearchResponse> search(@Valid @RequestBody SearchRequest req) {
    int queryLen = req.getQuery() == null ? 0 : req.getQuery().length();
    boolean hasQuery = req.getQuery() != null && !req.getQuery().isBlank();
    boolean hasDeprecatedImageQuery =
        req.getQueryImageBase64() != null && !req.getQueryImageBase64().isBlank();
    if (!hasQuery && !hasDeprecatedImageQuery) {
      throw new BizException(400, "QUERY_REQUIRED");
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

    enrichImageUrls(output.getResults());
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

  @PostMapping("/search/by-image")
  @PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR','KB_VIEWER','SERVICE_ACCOUNT')")
  public Result<SearchResponse> searchByImage(@Valid @RequestBody ImageSearchRequest req) {
    if (req.getQueryImageBase64() == null || req.getQueryImageBase64().isBlank()) {
      throw new BizException(400, "IMAGE_QUERY_REQUIRED");
    }
    boolean requestedSpecificKbs = req.getKbIds() != null && !req.getKbIds().isEmpty();
    Set<Long> readableKbIds =
        requestedSpecificKbs ? kbAccessGuard.filterReadable(req.getKbIds()) : kbAccessGuard.allReadableKbIds();
    if (readableKbIds.isEmpty()) {
      throw new BizException(403, "KB_ACCESS_DENIED");
    }
    long start = System.currentTimeMillis();
    List<SearchResult> results =
        vectorSearchService.searchImage(
            req.getQuery(),
            req.getQueryImageBase64(),
            new ArrayList<>(readableKbIds),
            req.getDocIds(),
            req.getTopK(),
            req.getFilter());
    long latencyMs = System.currentTimeMillis() - start;
    enrichImageUrls(results);
    return Result.ok(new SearchResponse(results, latencyMs, "image-vector", null, null, latencyMs, null, null));
  }
}
