package com.ragforge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.TextNormalizer;
import com.ragforge.mapper.RetrievalLogMapper;
import com.ragforge.model.entity.RetrievalLog;
import com.ragforge.search.SearchResult;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalLogService {

  private final RetrievalLogMapper retrievalLogMapper;
  private final ObjectMapper objectMapper;

  @Async("retrievalLogExecutor")
  public void logAsync(
      String query,
      String strategy,
      List<Long> kbIds,
      List<String> rewrittenQueries,
      int topK,
      int resultCount,
      long latencyMs) {
    logAsync(query, strategy, kbIds, rewrittenQueries, topK, resultCount, latencyMs, null);
  }

  @Async("retrievalLogExecutor")
  public void logAsync(
      String query,
      String strategy,
      List<Long> kbIds,
      List<String> rewrittenQueries,
      int topK,
      int resultCount,
      long latencyMs,
      List<SearchResult> results) {
    logAsync(query, strategy, kbIds, rewrittenQueries, topK, resultCount, latencyMs, results, null);
  }

  @Async("retrievalLogExecutor")
  public void logAsync(
      String query,
      String strategy,
      List<Long> kbIds,
      List<String> rewrittenQueries,
      int topK,
      int resultCount,
      long latencyMs,
      List<SearchResult> results,
      Long userId) {
    logAsync(
        query, strategy, kbIds, rewrittenQueries, topK, resultCount, latencyMs, results, userId, null,
        null);
  }

  @Async("retrievalLogExecutor")
  public void logAsync(
      String query,
      String strategy,
      List<Long> kbIds,
      List<String> rewrittenQueries,
      int topK,
      int resultCount,
      long latencyMs,
      List<SearchResult> results,
      Long userId,
      Double avgRerankScore,
      Long orgId) {
    RetrievalLog log = new RetrievalLog();
    log.setUserId(userId);
    log.setOrgId(orgId);
    log.setQuery(query);
    log.setStrategy(strategy);
    if (kbIds != null && !kbIds.isEmpty()) {
      log.setKbIds(kbIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
    }
    if (rewrittenQueries != null && !rewrittenQueries.isEmpty()) {
      log.setRewrittenQueries(String.join("\n", rewrittenQueries));
    }
    log.setTopK(topK);
    log.setResultCount(resultCount);
    log.setLatencyMs((int) latencyMs);
    log.setCitationsSnapshot(buildCitationsSnapshot(results));
    log.setAvgRerankScore(avgRerankScore);
    log.setStatus("SUCCESS");
    log.setCreatedAt(LocalDateTime.now());
    retrievalLogMapper.insert(log);
  }

  /** 检索失败补记一条 ERROR，供「检索成功率」统计；日志落库自身异常不得连累主流程。 */
  @Async("retrievalLogExecutor")
  public void logFailureAsync(
      String query, String strategy, List<Long> kbIds, long latencyMs, Long userId, Long orgId) {
    try {
      RetrievalLog entry = new RetrievalLog();
      entry.setUserId(userId);
      entry.setOrgId(orgId);
      entry.setQuery(query);
      entry.setStrategy(strategy);
      if (kbIds != null && !kbIds.isEmpty()) {
        entry.setKbIds(kbIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
      }
      entry.setResultCount(0);
      entry.setLatencyMs((int) latencyMs);
      entry.setStatus("ERROR");
      entry.setCreatedAt(LocalDateTime.now());
      retrievalLogMapper.insert(entry);
    } catch (Exception e) {
      log.warn("检索失败日志落库失败: {}", e.getMessage());
    }
  }

  private String buildCitationsSnapshot(List<SearchResult> results) {
    if (results == null || results.isEmpty()) {
      return null;
    }
    List<Map<String, Object>> snapshots =
        results.stream()
            .map(this::toCitationSnapshot)
            .toList();
    try {
      return objectMapper.writeValueAsString(snapshots);
    } catch (JsonProcessingException e) {
      log.warn("引用快照序列化失败: {}", e.getMessage());
      return null;
    }
  }

  private Map<String, Object> toCitationSnapshot(SearchResult item) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("chunkId", item.getChunkId());
    snapshot.put("docId", item.getDocId());
    snapshot.put("textSnippet", TextNormalizer.snippet(item.getContent(), 300));
    snapshot.put("score", primaryScore(item));
    return snapshot;
  }

  private static double primaryScore(SearchResult item) {
    if (item == null) {
      return 0;
    }
    if (item.getFinalScore() != 0) {
      return item.getFinalScore();
    }
    if (item.getBm25Score() != 0) {
      return item.getBm25Score();
    }
    return item.getVectorScore();
  }
}
