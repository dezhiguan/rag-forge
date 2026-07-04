package com.ragforge.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class RerankerClient {

  private final RestTemplate restTemplate;
  private final com.ragforge.modelcenter.ModelUsageRecorder modelUsageRecorder;

  @Value("${app.dashscope.api-key:}")
  private String apiKey;

  @Value("${app.dashscope.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
  private String dashscopeBaseUrl;

  @Value("${app.dashscope.rerank-model:gte-rerank-v2}")
  private String model;

  // qwen3-rerank 为指令微调模型,按其输入范式给 query 前缀一条"答案相关性"指令,引导它按
  // "文档是否直接、准确地回答用户问题"排序(而非仅字面/主题相似),缓解"答案埋在密集大块里"的排序问题。
  @Value("${app.dashscope.rerank-instruction:根据文档是否直接、准确地包含用户问题的答案来判断相关性;越能直接回答问题的文档相关性越高。}")
  private String rerankInstruction;

  // 缓存 rerank 结果：key 含 query + 候选文档内容 + topN。同 query 在稳定语料上召回同一批候选即命中，
  // 跳过 DashScope rerank 调用（命中不进方法体 → 不记 token，正确）；候选集变化会自动换 key 失效，无 stale。
  @Cacheable(value = "rerankResult", key = "{#query, #topN, #documents}", unless = "#result == null")
  public RerankOutput rerank(String query, List<String> documents, int topN) {
    long start = System.currentTimeMillis();
    if (documents == null || documents.isEmpty()) {
      log.info("Reranker skipped: reason=empty_documents latency=0ms");
      return new RerankOutput(List.of(), 0L);
    }

    try {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setBearerAuth(apiKey);

      String effectiveQuery =
          (rerankInstruction == null || rerankInstruction.isBlank())
              ? query
              : "Instruct: " + rerankInstruction + "\nQuery: " + query;
      var input = Map.of("query", effectiveQuery, "documents", documents);
      var params = Map.of("top_n", topN);
      var body = Map.of("model", model, "input", input, "parameters", params);

      HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

      String url = dashscopeBaseUrl.replace("/compatible-mode/v1", "")
          + "/api/v1/services/rerank/text-rerank/text-rerank";

      ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
      Map<String, Object> respBody = response.getBody();

      if (respBody == null) {
        long latencyMs = System.currentTimeMillis() - start;
        log.warn("Reranker returned empty response, fallback to original order: latency={}ms", latencyMs);
        return new RerankOutput(fallback(topN, documents.size()), latencyMs);
      }

      Map<String, Object> output = (Map<String, Object>) respBody.get("output");
      if (output == null) {
        long latencyMs = System.currentTimeMillis() - start;
        log.warn("Reranker output is null, fallback: latency={}ms body={}", latencyMs, respBody);
        return new RerankOutput(fallback(topN, documents.size()), latencyMs);
      }

      List<Map<String, Object>> rawResults = (List<Map<String, Object>>) output.get("results");
      if (rawResults == null || rawResults.isEmpty()) {
        long latencyMs = System.currentTimeMillis() - start;
        log.info("Reranker fallback: reason=empty_results latency={}ms", latencyMs);
        return new RerankOutput(fallback(topN, documents.size()), latencyMs);
      }

      List<RerankResult> results = new ArrayList<>();
      for (Map<String, Object> r : rawResults) {
        RerankResult item = new RerankResult();
        item.setIndex(toInt(r.get("index")));
        item.setScore(toDouble(r.get("relevance_score")));
        results.add(item);
      }

      long latencyMs = System.currentTimeMillis() - start;
      // 计量并联：input=usage.total_tokens（缺失按文档字符数估算），rerank 无输出 token
      long inputTokens = rerankUsageTokens(respBody, documents);
      modelUsageRecorder.record(
          new com.ragforge.modelcenter.ModelUsageEvent(
              model, com.ragforge.modelcenter.Purpose.RERANK, inputTokens, 0, latencyMs, true));
      log.info("Reranker completed: docCount={} topN={} latency={}ms scores={}",
          documents.size(),
          results.size(),
          latencyMs,
          results.stream().map(r -> String.format("%.4f", r.getScore())).toList());
      return new RerankOutput(results, latencyMs);
    } catch (Exception e) {
      long latencyMs = System.currentTimeMillis() - start;
      log.warn("Reranker unavailable, fallback to original order: latency={}ms err={}", latencyMs, e.getMessage());
      return new RerankOutput(fallback(topN, documents.size()), latencyMs);
    }
  }

  @SuppressWarnings("unchecked")
  private static long rerankUsageTokens(Map<String, Object> respBody, List<String> documents) {
    Object usageObj = respBody.get("usage");
    if (usageObj instanceof Map<?, ?> usage) {
      Object t = ((Map<String, Object>) usage).get("total_tokens");
      if (t == null) {
        t = ((Map<String, Object>) usage).get("input_tokens");
      }
      if (t instanceof Number n && n.longValue() > 0) {
        return n.longValue();
      }
    }
    long est = 0;
    for (String d : documents) {
      if (d != null) {
        est += Math.max(1, d.length() / 4);
      }
    }
    return est;
  }

  private static int toInt(Object v) {
    if (v instanceof Number n) return n.intValue();
    return 0;
  }

  private static double toDouble(Object v) {
    if (v instanceof Number n) return n.doubleValue();
    return 0.0;
  }

  private static List<RerankResult> fallback(int topN, int docSize) {
    int limit = Math.min(topN, docSize);
    List<RerankResult> results = new ArrayList<>(limit);
    for (int i = 0; i < limit; i++) {
      RerankResult item = new RerankResult();
      item.setIndex(i);
      item.setScore(1.0 - (i * 0.01));
      results.add(item);
    }
    return results;
  }

  @Data
  public static class RerankResult {
    private int index;
    private double score;
  }

  @Data
  @RequiredArgsConstructor
  public static class RerankOutput {
    private final List<RerankResult> results;
    private final Long latencyMs;
  }
}
