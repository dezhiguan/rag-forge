package com.ragforge.search;

import com.ragforge.config.RetrievalProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class HybridSearchService {

  private static final int RRF_K = 10;

  private final VectorSearchService vectorSearchService;
  private final EsSearchService esSearchService;
  private final RetrievalProperties retrievalProperties;
  private final Executor retrievalExecutor;

  public HybridSearchService(
      VectorSearchService vectorSearchService,
      EsSearchService esSearchService,
      RetrievalProperties retrievalProperties,
      @Qualifier("retrievalExecutor") Executor retrievalExecutor) {
    this.vectorSearchService = vectorSearchService;
    this.esSearchService = esSearchService;
    this.retrievalProperties = retrievalProperties;
    this.retrievalExecutor = retrievalExecutor;
  }

  public List<SearchResult> search(String query, List<Long> kbIds, List<Long> docIds, int topK, double vectorWeight) {
    return searchWithMetrics(query, kbIds, docIds, topK, vectorWeight).getResults();
  }

  public HybridSearchOutput searchWithMetrics(
      String query, List<Long> kbIds, List<Long> docIds, int topK, double vectorWeight) {
    int recallTopK = Math.max(topK * 4, 20);
    double vw = clampWeight(vectorWeight);

    // Extreme weights should fully degrade to a single strategy.
    if (vw >= 1.0) {
      long start = System.currentTimeMillis();
      List<SearchResult> results = vectorSearchService.search(query, kbIds, docIds, topK);
      long vectorLatency = System.currentTimeMillis() - start;
      for (SearchResult r : results) {
        r.setFinalScore(r.getVectorScore());
      }
      return new HybridSearchOutput(results, vectorLatency, null, "vector");
    }
    if (vw <= 0.0) {
      long start = System.currentTimeMillis();
      List<SearchResult> results = esSearchService.search(query, kbIds, docIds, topK);
      long keywordLatency = System.currentTimeMillis() - start;
      for (SearchResult r : results) {
        r.setFinalScore(r.getBm25Score());
      }
      return new HybridSearchOutput(results, null, keywordLatency, "keyword");
    }

    AtomicLong vectorLatency = new AtomicLong(0);
    AtomicLong keywordLatency = new AtomicLong(0);

    CompletableFuture<List<SearchResult>> vectorFuture =
        CompletableFuture.supplyAsync(
            () -> {
              long start = System.currentTimeMillis();
              List<SearchResult> result = vectorSearchService.search(query, kbIds, docIds, recallTopK);
              vectorLatency.set(System.currentTimeMillis() - start);
              return result;
            },
            retrievalExecutor);
    CompletableFuture<List<SearchResult>> keywordFuture =
        CompletableFuture.supplyAsync(
            () -> {
              long start = System.currentTimeMillis();
              List<SearchResult> result = esSearchService.search(query, kbIds, docIds, recallTopK);
              keywordLatency.set(System.currentTimeMillis() - start);
              return result;
            },
            retrievalExecutor);

    List<SearchResult> vectorResults;
    List<SearchResult> keywordResults;
    try {
      vectorResults = vectorFuture.get(retrievalProperties.getStageTimeoutMs(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      vectorFuture.cancel(true);
      log.error("向量检索超时 ({}ms)，降级为空结果", retrievalProperties.getStageTimeoutMs());
      vectorResults = Collections.emptyList();
    } catch (Exception e) {
      log.error("向量检索异常: {}", e.getMessage());
      vectorResults = Collections.emptyList();
    }
    try {
      keywordResults = keywordFuture.get(retrievalProperties.getStageTimeoutMs(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      keywordFuture.cancel(true);
      log.error("关键词检索超时 ({}ms)，降级为空结果", retrievalProperties.getStageTimeoutMs());
      keywordResults = Collections.emptyList();
    } catch (Exception e) {
      log.error("关键词检索异常: {}", e.getMessage());
      keywordResults = Collections.emptyList();
    }

    Map<Long, SearchResult> merged = new LinkedHashMap<>();
    Map<Long, Double> rrfScores = new HashMap<>();
    for (int i = 0; i < vectorResults.size(); i++) {
      SearchResult item = vectorResults.get(i);
      if (item.getChunkId() == null) {
        continue;
      }
      SearchResult base = merged.computeIfAbsent(item.getChunkId(), id -> cloneForHybrid(item));
      base.setVectorScore(item.getVectorScore());
      rrfScores.merge(item.getChunkId(), 1.0 / (RRF_K + i + 1), Double::sum);
    }

    for (int i = 0; i < keywordResults.size(); i++) {
      SearchResult item = keywordResults.get(i);
      if (item.getChunkId() == null) {
        continue;
      }
      SearchResult base = merged.computeIfAbsent(item.getChunkId(), id -> cloneForHybrid(item));
      base.setBm25Score(item.getBm25Score());
      if (base.getFilename() == null || base.getFilename().isBlank()) {
        base.setFilename(item.getFilename());
      }
      if (base.getContent() == null || base.getContent().isBlank()) {
        base.setContent(item.getContent());
      }
      if (base.getDocId() == null) {
        base.setDocId(item.getDocId());
      }
      if (base.getChunkIndex() == 0 && item.getChunkIndex() != 0) {
        base.setChunkIndex(item.getChunkIndex());
      }
      rrfScores.merge(item.getChunkId(), 1.0 / (RRF_K + i + 1), Double::sum);
    }

    List<SearchResult> sorted = new ArrayList<>(merged.values());
    for (SearchResult result : sorted) {
      result.setFinalScore(rrfScores.getOrDefault(result.getChunkId(), 0.0));
    }
    sorted.sort(Comparator.comparingDouble(SearchResult::getFinalScore).reversed());
    if (sorted.size() > topK) {
      sorted = new ArrayList<>(sorted.subList(0, topK));
    }
    return new HybridSearchOutput(sorted, vectorLatency.get(), keywordLatency.get(), "hybrid");
  }

  private static SearchResult cloneForHybrid(SearchResult src) {
    SearchResult copy = new SearchResult();
    copy.setChunkId(src.getChunkId());
    copy.setDocId(src.getDocId());
    copy.setFilename(src.getFilename());
    copy.setContent(src.getContent());
    copy.setChunkIndex(src.getChunkIndex());
    copy.setVectorScore(src.getVectorScore());
    copy.setBm25Score(src.getBm25Score());
    return copy;
  }

  private static double clampWeight(double w) {
    if (w < 0) {
      return 0;
    }
    if (w > 1) {
      return 1;
    }
    return w;
  }

  @Value
  public static class HybridSearchOutput {
    List<SearchResult> results;
    Long vectorLatencyMs;
    Long keywordLatencyMs;
    String effectiveStrategy;
  }
}
