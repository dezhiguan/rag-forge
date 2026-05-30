package com.ragforge.search;

import com.ragforge.search.HybridSearchService.HybridSearchOutput;
import com.ragforge.search.RerankerClient.RerankOutput;
import com.ragforge.search.RerankerClient.RerankResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

  private static final int RRF_K = 10;

  private final VectorSearchService vectorSearchService;
  private final EsSearchService esSearchService;
  private final HybridSearchService hybridSearchService;
  private final QueryRewriter queryRewriter;
  private final RerankerClient rerankerClient;
  private final MeterRegistry meterRegistry;

  public RetrievalOutput retrieve(
      String query,
      List<Long> kbIds,
      List<Long> docIds,
      String strategy,
      Double vectorWeight,
      int topK,
      int rerankTopN) {
    long start = System.currentTimeMillis();
    String normalizedStrategy = normalizeStrategy(strategy);
    double normalizedVectorWeight = normalizeVectorWeight(vectorWeight);

    log.info("检索请求 strategy={} topK={} query=\"{}\"", normalizedStrategy, topK, query);

    List<String> rewrittenQueries = null;
    Long rewriteLatencyMs = null;
    Long vectorLatencyMs = null;
    Long keywordLatencyMs = null;
    Long rerankLatencyMs = null;
    List<SearchResult> results;

    if ("rewrite".equals(normalizedStrategy)) {
      long rewriteStart = System.currentTimeMillis();
      rewrittenQueries = queryRewriter.rewrite(query);
      rewriteLatencyMs = System.currentTimeMillis() - rewriteStart;
      log.info("Query改写完成 变体数={} latency={}ms", rewrittenQueries.size(), rewriteLatencyMs);
      long vectorStart = System.currentTimeMillis();
      results = searchByRewrittenQueries(rewrittenQueries, kbIds, docIds, topK);
      vectorLatencyMs = System.currentTimeMillis() - vectorStart;
    } else if ("full".equals(normalizedStrategy)) {
      long rewriteStart = System.currentTimeMillis();
      rewrittenQueries = queryRewriter.rewrite(query);
      rewriteLatencyMs = System.currentTimeMillis() - rewriteStart;
      log.info("Query改写完成 变体数={} latency={}ms", rewrittenQueries.size(), rewriteLatencyMs);
      HybridSearchOutput output =
          searchByRewrittenHybrid(
              rewrittenQueries, kbIds, docIds, topK, normalizedVectorWeight);
      results = output.getResults();
      vectorLatencyMs = output.getVectorLatencyMs();
      keywordLatencyMs = output.getKeywordLatencyMs();
      log.info(
          "混合召回完成 候选数={} vectorLatency={}ms keywordLatency={}ms",
          results.size(),
          vectorLatencyMs,
          keywordLatencyMs);

      List<String> documents = results.stream().map(SearchResult::getContent).toList();
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
              query, kbIds, docIds, topK, normalizedVectorWeight);
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
      results = esSearchService.search(query, kbIds, docIds, topK);
      keywordLatencyMs = System.currentTimeMillis() - keywordStart;
      log.info("关键词检索完成 结果数={} latency={}ms", results.size(), keywordLatencyMs);
    } else {
      long vectorStart = System.currentTimeMillis();
      results = vectorSearchService.search(query, kbIds, docIds, topK);
      vectorLatencyMs = System.currentTimeMillis() - vectorStart;
      log.info("向量检索完成 结果数={} latency={}ms", results.size(), vectorLatencyMs);
    }

    long latencyMs = System.currentTimeMillis() - start;
    log.info("检索完成 resultCount={} totalLatency={}ms", results.size(), latencyMs);
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
      List<String> queries, List<Long> kbIds, List<Long> docIds, int topK) {
    return vectorSearchService.searchByQueries(queries, kbIds, docIds, topK);
  }

  private HybridSearchOutput searchByRewrittenHybrid(
      List<String> queries, List<Long> kbIds, List<Long> docIds, int topK, double vectorWeight) {
    int recallTopK = Math.max(topK * 2, topK);
    Map<Long, SearchResult> dedup = new LinkedHashMap<>();
    Map<Long, Double> rrfScores = new LinkedHashMap<>();
    long vectorLatency = 0;
    long keywordLatency = 0;

    if (vectorWeight > 0) {
      long vectorStart = System.currentTimeMillis();
      List<SearchResult> vectorResults =
          vectorSearchService.searchByQueries(queries, kbIds, docIds, recallTopK);
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

    for (String q : queries) {
      long keywordStart = System.currentTimeMillis();
      List<SearchResult> keywordResults = esSearchService.search(q, kbIds, docIds, recallTopK);
      keywordLatency += System.currentTimeMillis() - keywordStart;
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
    return copy;
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
