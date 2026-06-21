package com.ragforge.search;

import com.ragforge.search.HybridSearchService.HybridSearchOutput;
import com.ragforge.common.BizException;
import com.ragforge.config.RetrievalProperties;
import com.ragforge.search.RerankerClient.RerankOutput;
import com.ragforge.search.RerankerClient.RerankResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RetrievalService {

  private static final int RRF_K = 10;

  private final VectorSearchService vectorSearchService;
  private final EsSearchService esSearchService;
  private final HybridSearchService hybridSearchService;
  private final QueryRewriter queryRewriter;
  private final RerankerClient rerankerClient;
  private final MeterRegistry meterRegistry;
  private final RetrievalProperties retrievalProperties;
  private final Executor retrievalExecutor;
  private final Semaphore keywordSemaphore;
  private final Semaphore vectorSemaphore;
  private final Semaphore hybridSemaphore;
  private final Semaphore fullSemaphore;
  private final Semaphore rewriteSemaphore;

  public RetrievalService(
      VectorSearchService vectorSearchService,
      EsSearchService esSearchService,
      HybridSearchService hybridSearchService,
      QueryRewriter queryRewriter,
      RerankerClient rerankerClient,
      MeterRegistry meterRegistry,
      RetrievalProperties retrievalProperties,
      @Qualifier("retrievalExecutor") Executor retrievalExecutor) {
    this.vectorSearchService = vectorSearchService;
    this.esSearchService = esSearchService;
    this.hybridSearchService = hybridSearchService;
    this.queryRewriter = queryRewriter;
    this.rerankerClient = rerankerClient;
    this.meterRegistry = meterRegistry;
    this.retrievalProperties = retrievalProperties;
    this.retrievalExecutor = retrievalExecutor;
    this.keywordSemaphore = new Semaphore(Math.max(1, retrievalProperties.getKeyword().getMaxConcurrent()));
    this.vectorSemaphore = new Semaphore(Math.max(1, retrievalProperties.getVector().getMaxConcurrent()));
    this.hybridSemaphore = new Semaphore(Math.max(1, retrievalProperties.getHybrid().getMaxConcurrent()));
    this.fullSemaphore = new Semaphore(Math.max(1, retrievalProperties.getFull().getMaxConcurrent()));
    this.rewriteSemaphore = new Semaphore(Math.max(1, retrievalProperties.getRewrite().getMaxConcurrent()));
  }

  public RetrievalOutput retrieve(
      String query,
      List<Long> kbIds,
      List<Long> docIds,
      String strategy,
      Double vectorWeight,
      int topK,
      int rerankTopN) {
    return retrieve(query, kbIds, docIds, strategy, vectorWeight, topK, rerankTopN, null);
  }

  public RetrievalOutput retrieve(
      String query,
      List<Long> kbIds,
      List<Long> docIds,
      String strategy,
      Double vectorWeight,
      int topK,
      int rerankTopN,
      com.ragforge.model.dto.SearchRequest.SearchFilter filter) {
    return retrieve(
        query, null, kbIds, docIds, strategy, vectorWeight, topK, rerankTopN, filter, null);
  }

  public RetrievalOutput retrieve(
      String query,
      String queryImageBase64,
      List<Long> kbIds,
      List<Long> docIds,
      String strategy,
      Double vectorWeight,
      int topK,
      int rerankTopN,
      com.ragforge.model.dto.SearchRequest.SearchFilter filter,
      String modality) {
    requireKbScope(kbIds);
    String normalizedStrategy = normalizeStrategy(strategy);
    String normalizedModality = normalizeModality(modality);
    Semaphore semaphore = semaphoreFor(normalizedStrategy);
    int timeoutMs = timeoutMsFor(normalizedStrategy);
    boolean acquired = false;
    CompletableFuture<RetrievalOutput> future = null;
    try {
      acquired = semaphore.tryAcquire(200, TimeUnit.MILLISECONDS);
      if (!acquired) {
        meterRegistry.counter("ragforge.retrieval.rejected", "strategy", normalizedStrategy).increment();
        throw new BizException(429, "检索请求过多，请稍后重试");
      }
      future =
          CompletableFuture.supplyAsync(
              () ->
                  retrieveInternal(
                      query,
                      queryImageBase64,
                      kbIds,
                      docIds,
                      normalizedStrategy,
                      vectorWeight,
                      topK,
                      rerankTopN,
                      filter,
                      normalizedModality),
              retrievalExecutor);
      return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      if (future != null) {
        future.cancel(true);
      }
      meterRegistry.counter("ragforge.retrieval.timeout", "strategy", normalizedStrategy).increment();
      log.warn("检索总超时 strategy={} timeout={}ms queryLen={}", normalizedStrategy, timeoutMs, queryLength(query));
      throw new BizException(504, "检索超时，请缩小范围或稍后重试");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof BizException bizException) {
        throw bizException;
      }
      throw new BizException(500, "检索失败：" + (cause == null ? e.getMessage() : cause.getMessage()));
    } catch (BizException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BizException(500, "检索被中断");
    } catch (Exception e) {
      throw new BizException(500, "检索失败：" + e.getMessage());
    } finally {
      if (acquired) {
        semaphore.release();
      }
    }
  }

  private static void requireKbScope(List<Long> kbIds) {
    if (kbIds == null || kbIds.isEmpty()) {
      throw new IllegalStateException("Retrieval requires non-empty kbIds after authorization filtering");
    }
  }

  private RetrievalOutput retrieveInternal(
      String query,
      String queryImageBase64,
      List<Long> kbIds,
      List<Long> docIds,
      String normalizedStrategy,
      Double vectorWeight,
      int topK,
      int rerankTopN,
      com.ragforge.model.dto.SearchRequest.SearchFilter filter,
      String modality) {
    long start = System.currentTimeMillis();
    double normalizedVectorWeight = normalizeVectorWeight(vectorWeight);

    log.info(
        "检索请求 strategy={} topK={} rerankTopN={} kbCount={} docFilterCount={} queryLen={}",
        normalizedStrategy,
        topK,
        rerankTopN,
        kbIds == null ? 0 : kbIds.size(),
        docIds == null ? 0 : docIds.size(),
        queryLength(query));

    List<String> rewrittenQueries = null;
    Long rewriteLatencyMs = null;
    Long vectorLatencyMs = null;
    Long keywordLatencyMs = null;
    Long rerankLatencyMs = null;
    List<SearchResult> results;

    if ("image".equals(modality)) {
      long vectorStart = System.currentTimeMillis();
      results = vectorSearchService.searchImage(query, queryImageBase64, kbIds, docIds, topK, filter);
      vectorLatencyMs = System.currentTimeMillis() - vectorStart;
      normalizedStrategy = "image";
    } else if ("both".equals(modality)) {
      long vectorStart = System.currentTimeMillis();
      results = searchMultimodal(query, queryImageBase64, kbIds, docIds, topK, filter);
      vectorLatencyMs = System.currentTimeMillis() - vectorStart;
      normalizedStrategy = "both";
    } else if ("rewrite".equals(normalizedStrategy)) {
      long rewriteStart = System.currentTimeMillis();
      rewrittenQueries = queryRewriter.rewrite(query);
      rewriteLatencyMs = System.currentTimeMillis() - rewriteStart;
      log.info("Query改写完成 变体数={} latency={}ms", rewrittenQueries.size(), rewriteLatencyMs);
      long vectorStart = System.currentTimeMillis();
      results = searchByRewrittenQueries(rewrittenQueries, kbIds, docIds, topK, filter);
      vectorLatencyMs = System.currentTimeMillis() - vectorStart;
    } else if ("full".equals(normalizedStrategy)) {
      long rewriteStart = System.currentTimeMillis();
      rewrittenQueries = queryRewriter.rewrite(query);
      rewriteLatencyMs = System.currentTimeMillis() - rewriteStart;
      log.info("Query改写完成 变体数={} latency={}ms", rewrittenQueries.size(), rewriteLatencyMs);
      HybridSearchOutput output =
          searchByRewrittenHybrid(
              rewrittenQueries, kbIds, docIds, topK, normalizedVectorWeight, filter);
      results = output.getResults();
      vectorLatencyMs = output.getVectorLatencyMs();
      keywordLatencyMs = output.getKeywordLatencyMs();
      log.info(
          "混合召回完成 候选数={} vectorLatency={}ms keywordLatency={}ms",
          results.size(),
          vectorLatencyMs,
          keywordLatencyMs);

      List<String> documents =
          results.stream()
              .map(
                  r -> {
                    String c = r.getContent();
                    return (c != null && c.length() > 300) ? c.substring(0, 300) : c;
                  })
              .toList();
      long rerankStart = System.currentTimeMillis();
      RerankOutput rerankOutput =
          rerankerClient.rerank(query, documents, Math.min(rerankTopN, results.size()));
      rerankLatencyMs = rerankOutput.getLatencyMs();
      if (rerankLatencyMs == null) {
        rerankLatencyMs = System.currentTimeMillis() - rerankStart;
      }
      results = applyRerankResults(results, rerankOutput.getResults(), topK);
      log.info("Reranker完成 topK={} rerankLatency={}ms", results.size(), rerankLatencyMs);
    } else if ("hybrid".equals(normalizedStrategy)) {
      HybridSearchOutput output =
          hybridSearchService.searchWithMetrics(
              query, kbIds, docIds, topK, normalizedVectorWeight, filter);
      results = output.getResults();
      vectorLatencyMs = output.getVectorLatencyMs();
      keywordLatencyMs = output.getKeywordLatencyMs();
      normalizedStrategy = output.getEffectiveStrategy();
      log.info(
          "混合检索完成 结果数={} vectorLatency={}ms keywordLatency={}ms",
          results.size(),
          vectorLatencyMs,
          keywordLatencyMs);
    } else if ("keyword".equals(normalizedStrategy)) {
      long keywordStart = System.currentTimeMillis();
      results = esSearchService.search(query, kbIds, docIds, topK, filter);
      keywordLatencyMs = System.currentTimeMillis() - keywordStart;
      log.info("关键词检索完成 结果数={} latency={}ms", results.size(), keywordLatencyMs);
    } else {
      long vectorStart = System.currentTimeMillis();
      results = vectorSearchService.search(query, kbIds, docIds, topK, filter);
      vectorLatencyMs = System.currentTimeMillis() - vectorStart;
      log.info("向量检索完成 结果数={} latency={}ms", results.size(), vectorLatencyMs);
    }

    long latencyMs = System.currentTimeMillis() - start;
    log.info(
        "检索完成 strategy={} resultCount={} rewriteLatency={}ms vectorLatency={}ms keywordLatency={}ms rerankLatency={}ms totalLatency={}ms",
        normalizedStrategy,
        results.size(),
        rewriteLatencyMs,
        vectorLatencyMs,
        keywordLatencyMs,
        rerankLatencyMs,
        latencyMs);
    recordMetrics(
        normalizedStrategy,
        latencyMs,
        rewriteLatencyMs,
        vectorLatencyMs,
        keywordLatencyMs,
        rerankLatencyMs,
        results.size());

    return new RetrievalOutput(
        results,
        latencyMs,
        normalizedStrategy,
        rewrittenQueries,
        rewriteLatencyMs,
        vectorLatencyMs,
        keywordLatencyMs,
        rerankLatencyMs);
  }

  private Semaphore semaphoreFor(String strategy) {
    return switch (strategy) {
      case "keyword" -> keywordSemaphore;
      case "hybrid" -> hybridSemaphore;
      case "full" -> fullSemaphore;
      case "rewrite" -> rewriteSemaphore;
      default -> vectorSemaphore;
    };
  }

  private int timeoutMsFor(String strategy) {
    return switch (strategy) {
      case "keyword" -> retrievalProperties.getKeyword().getTimeoutMs();
      case "hybrid" -> retrievalProperties.getHybrid().getTimeoutMs();
      case "full" -> retrievalProperties.getFull().getTimeoutMs();
      case "rewrite" -> retrievalProperties.getRewrite().getTimeoutMs();
      default -> retrievalProperties.getVector().getTimeoutMs();
    };
  }

  private void recordMetrics(
      String strategy,
      long totalLatencyMs,
      Long rewriteLatencyMs,
      Long vectorLatencyMs,
      Long keywordLatencyMs,
      Long rerankLatencyMs,
      int resultCount) {
    meterRegistry
        .timer("ragforge.retrieval.latency", "strategy", strategy, "stage", "total")
        .record(totalLatencyMs, TimeUnit.MILLISECONDS);
    recordStageMetric(strategy, "rewrite", rewriteLatencyMs);
    recordStageMetric(strategy, "vector", vectorLatencyMs);
    recordStageMetric(strategy, "keyword", keywordLatencyMs);
    recordStageMetric(strategy, "rerank", rerankLatencyMs);
    meterRegistry.counter("ragforge.retrieval.requests", "strategy", strategy).increment();
    meterRegistry
        .summary("ragforge.retrieval.result.count", "strategy", strategy)
        .record(resultCount);
  }

  private void recordStageMetric(String strategy, String stage, Long latencyMs) {
    if (latencyMs == null) {
      return;
    }
    meterRegistry
        .timer("ragforge.retrieval.latency", "strategy", strategy, "stage", stage)
        .record(latencyMs, TimeUnit.MILLISECONDS);
  }

  private List<SearchResult> searchByRewrittenQueries(
      List<String> queries,
      List<Long> kbIds,
      List<Long> docIds,
      int topK,
      com.ragforge.model.dto.SearchRequest.SearchFilter filter) {
    return vectorSearchService.searchByQueries(queries, kbIds, docIds, topK, filter);
  }

  private HybridSearchOutput searchByRewrittenHybrid(
      List<String> queries,
      List<Long> kbIds,
      List<Long> docIds,
      int topK,
      double vectorWeight,
      com.ragforge.model.dto.SearchRequest.SearchFilter filter) {
    int recallTopK = Math.max(topK * 4, 20);
    Map<Long, SearchResult> dedup = new LinkedHashMap<>();
    Map<Long, Double> rrfScores = new LinkedHashMap<>();
    long vectorLatency = 0;
    long keywordLatency = 0;

    if (vectorWeight > 0) {
      long vectorStart = System.currentTimeMillis();
      List<SearchResult> vectorResults =
          vectorSearchService.searchByQueries(queries, kbIds, docIds, recallTopK, filter);
      vectorLatency = System.currentTimeMillis() - vectorStart;
      for (int i = 0; i < vectorResults.size(); i++) {
        SearchResult item = vectorResults.get(i);
        if (item.getChunkId() == null) {
          continue;
        }
        SearchResult base = dedup.computeIfAbsent(item.getChunkId(), id -> cloneResult(item));
        base.setVectorScore(Math.max(base.getVectorScore(), item.getVectorScore()));
        if (vectorWeight >= 1.0) {
          base.setFinalScore(base.getVectorScore());
        } else {
          rrfScores.merge(item.getChunkId(), 1.0 / (RRF_K + i + 1), Double::sum);
        }
      }
    }

    if (vectorWeight >= 1.0) {
      List<SearchResult> vectorOnly = new ArrayList<>(dedup.values());
      vectorOnly.sort(Comparator.comparingDouble(SearchResult::getFinalScore).reversed());
      if (vectorOnly.size() > topK) {
        vectorOnly = new ArrayList<>(vectorOnly.subList(0, topK));
      }
      return new HybridSearchOutput(vectorOnly, vectorLatency, null, "vector");
    }

    List<CompletableFuture<List<SearchResult>>> esFutures =
        queries.stream()
            .map(
                q ->
                    CompletableFuture.supplyAsync(
                        () -> esSearchService.search(q, kbIds, docIds, recallTopK, filter),
                        retrievalExecutor))
            .toList();

    long keywordStart = System.currentTimeMillis();
    List<List<SearchResult>> allEsResults =
        esFutures.stream()
            .map(
                f -> {
                  try {
                    return f.get(retrievalProperties.getStageTimeoutMs(), TimeUnit.MILLISECONDS);
                  } catch (Exception e) {
                    log.warn("ES 子查询超时/异常: {}", e.getMessage());
                    return java.util.List.<SearchResult>of();
                  }
                })
            .toList();
    keywordLatency = System.currentTimeMillis() - keywordStart;

    for (List<SearchResult> keywordResults : allEsResults) {
      for (int i = 0; i < keywordResults.size(); i++) {
        SearchResult item = keywordResults.get(i);
        if (item.getChunkId() == null) {
          continue;
        }
        SearchResult base = dedup.computeIfAbsent(item.getChunkId(), id -> cloneResult(item));
        base.setBm25Score(Math.max(base.getBm25Score(), item.getBm25Score()));
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
    }

    List<SearchResult> merged = new ArrayList<>(dedup.values());
    for (SearchResult result : merged) {
      if (vectorWeight <= 0) {
        result.setFinalScore(result.getBm25Score());
      } else {
        result.setFinalScore(rrfScores.getOrDefault(result.getChunkId(), 0.0));
      }
    }
    merged.sort(Comparator.comparingDouble(SearchResult::getFinalScore).reversed());
    if (merged.size() > topK) {
      merged = new ArrayList<>(merged.subList(0, topK));
    }
    return new HybridSearchOutput(
        merged, vectorWeight <= 0 ? null : vectorLatency, keywordLatency, vectorWeight <= 0 ? "keyword" : "hybrid");
  }

  private static SearchResult cloneResult(SearchResult src) {
    SearchResult copy = new SearchResult();
    copy.setChunkId(src.getChunkId());
    copy.setDocId(src.getDocId());
    copy.setFilename(src.getFilename());
    copy.setContent(src.getContent());
    copy.setChunkIndex(src.getChunkIndex());
    copy.setVectorScore(src.getVectorScore());
    copy.setBm25Score(src.getBm25Score());
    copy.setFinalScore(src.getFinalScore());
    copy.setChunkType(src.getChunkType());
    copy.setChunkModality(src.getChunkModality());
    copy.setImageKey(src.getImageKey());
    return copy;
  }

  private List<SearchResult> searchMultimodal(
      String query,
      String queryImageBase64,
      List<Long> kbIds,
      List<Long> docIds,
      int topK,
      com.ragforge.model.dto.SearchRequest.SearchFilter filter) {
    int recallTopK = Math.max(topK * 4, 20);
    List<SearchResult> textResults = vectorSearchService.search(query, kbIds, docIds, recallTopK, filter);
    List<SearchResult> imageResults =
        vectorSearchService.searchImage(query, queryImageBase64, kbIds, docIds, recallTopK, filter);
    Map<Long, SearchResult> dedup = new LinkedHashMap<>();
    Map<Long, Double> scores = new LinkedHashMap<>();
    mergeRrf(textResults, dedup, scores, retrievalProperties.getMultimodalTextWeight());
    mergeRrf(imageResults, dedup, scores, retrievalProperties.getMultimodalImageWeight());
    List<SearchResult> merged = new ArrayList<>(dedup.values());
    for (SearchResult result : merged) {
      result.setFinalScore(scores.getOrDefault(result.getChunkId(), 0.0));
    }
    merged.sort(Comparator.comparingDouble(SearchResult::getFinalScore).reversed());
    return merged.size() > topK ? new ArrayList<>(merged.subList(0, topK)) : merged;
  }

  private static void mergeRrf(
      List<SearchResult> source,
      Map<Long, SearchResult> dedup,
      Map<Long, Double> scores,
      double weight) {
    if (source == null || source.isEmpty() || weight <= 0) {
      return;
    }
    for (int i = 0; i < source.size(); i++) {
      SearchResult item = source.get(i);
      if (item.getChunkId() == null) {
        continue;
      }
      dedup.computeIfAbsent(item.getChunkId(), id -> cloneResult(item));
      scores.merge(item.getChunkId(), weight * (1.0 / (RRF_K + i + 1)), Double::sum);
    }
  }

  private static List<SearchResult> applyRerankResults(
      List<SearchResult> source, List<RerankResult> reranked, int topK) {
    if (source == null || source.isEmpty() || reranked == null || reranked.isEmpty()) {
      return source;
    }
    List<SearchResult> reordered = new ArrayList<>();
    Set<Integer> used = new HashSet<>();
    for (RerankResult rank : reranked) {
      int idx = rank.getIndex();
      if (idx < 0 || idx >= source.size()) {
        continue;
      }
      SearchResult item = source.get(idx);
      item.setFinalScore(rank.getScore());
      reordered.add(item);
      used.add(idx);
    }
    for (int i = 0; i < source.size(); i++) {
      if (used.contains(i)) {
        continue;
      }
      reordered.add(source.get(i));
    }
    reordered.sort(Comparator.comparingDouble(SearchResult::getFinalScore).reversed());
    return reordered.size() > topK ? reordered.subList(0, topK) : reordered;
  }

  private static String normalizeStrategy(String strategy) {
    if ("keyword".equalsIgnoreCase(strategy)) {
      return "keyword";
    }
    if ("hybrid".equalsIgnoreCase(strategy)) {
      return "hybrid";
    }
    if ("full".equalsIgnoreCase(strategy)) {
      return "full";
    }
    if ("rewrite".equalsIgnoreCase(strategy)) {
      return "rewrite";
    }
    return "vector";
  }

  private static double normalizeVectorWeight(Double raw) {
    if (raw == null) {
      return 0.55;
    }
    return Math.max(0, Math.min(1, raw));
  }

  private static String normalizeModality(String raw) {
    if ("image".equalsIgnoreCase(raw)) {
      return "image";
    }
    if ("both".equalsIgnoreCase(raw)) {
      return "both";
    }
    return "text";
  }

  private static int queryLength(String query) {
    return query == null ? 0 : query.length();
  }

  @Value
  public static class RetrievalOutput {
    List<SearchResult> results;
    long latencyMs;
    String strategy;
    List<String> rewrittenQueries;
    Long rewriteLatencyMs;
    Long vectorLatencyMs;
    Long keywordLatencyMs;
    Long rerankLatencyMs;
  }
}
