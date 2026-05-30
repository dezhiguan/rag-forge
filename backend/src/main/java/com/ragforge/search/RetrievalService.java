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
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

  private final VectorSearchService vectorSearchService;
  private final EsSearchService esSearchService;
  private final HybridSearchService hybridSearchService;
  private final QueryRewriter queryRewriter;
  private final RerankerClient rerankerClient;

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

  private List<SearchResult> searchByRewrittenQueries(
      List<String> queries, List<Long> kbIds, List<Long> docIds, int topK) {
    Map<Long, SearchResult> dedup = new LinkedHashMap<>();
    for (String q : queries) {
      List<SearchResult> partial = vectorSearchService.search(q, kbIds, docIds, topK);
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
    return merged.size() > topK ? merged.subList(0, topK) : merged;
  }

  private HybridSearchOutput searchByRewrittenHybrid(
      List<String> queries, List<Long> kbIds, List<Long> docIds, int topK, double vectorWeight) {
    Map<Long, SearchResult> dedup = new LinkedHashMap<>();
    long vectorLatency = 0;
    long keywordLatency = 0;
    for (String q : queries) {
      HybridSearchOutput output =
          hybridSearchService.searchWithMetrics(q, kbIds, docIds, topK * 2, vectorWeight);
      vectorLatency += output.getVectorLatencyMs() == null ? 0 : output.getVectorLatencyMs();
      keywordLatency += output.getKeywordLatencyMs() == null ? 0 : output.getKeywordLatencyMs();
      for (SearchResult item : output.getResults()) {
        if (item.getChunkId() == null) {
          continue;
        }
        SearchResult existing = dedup.get(item.getChunkId());
        if (existing == null || item.getFinalScore() > existing.getFinalScore()) {
          dedup.put(item.getChunkId(), item);
        }
      }
    }
    List<SearchResult> merged = new ArrayList<>(dedup.values());
    merged.sort(Comparator.comparingDouble(SearchResult::getFinalScore).reversed());
    if (merged.size() > topK) {
      merged = merged.subList(0, topK);
    }
    return new HybridSearchOutput(merged, vectorLatency, keywordLatency, "hybrid");
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
