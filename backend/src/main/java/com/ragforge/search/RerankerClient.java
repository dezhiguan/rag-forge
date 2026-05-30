package com.ragforge.search;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

  @Value("${app.dashscope.api-key:}")
  private String apiKey;

  @Value("${app.dashscope.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
  private String dashscopeBaseUrl;

  @Value("${app.dashscope.rerank-model:gte-rerank-v2}")
  private String model;

  public RerankOutput rerank(String query, List<String> documents, int topN) {
    if (documents == null || documents.isEmpty()) {
      return new RerankOutput(List.of(), null);
    }

    try {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setBearerAuth(apiKey);

      var input = Map.of("query", query, "documents", documents);
      var params = Map.of("top_n", topN);
      var body = Map.of("model", model, "input", input, "parameters", params);

      HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

      String url = dashscopeBaseUrl.replace("/compatible-mode/v1", "")
          + "/api/v1/services/rerank/text-rerank/text-rerank";

      ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
      Map<String, Object> respBody = response.getBody();

      if (respBody == null) {
        log.warn("Reranker returned empty response, fallback to original order");
        return new RerankOutput(fallback(topN, documents.size()), null);
      }

      Map<String, Object> output = (Map<String, Object>) respBody.get("output");
      if (output == null) {
        log.warn("Reranker output is null, fallback: {}", respBody);
        return new RerankOutput(fallback(topN, documents.size()), null);
      }

      List<Map<String, Object>> rawResults = (List<Map<String, Object>>) output.get("results");
      if (rawResults == null || rawResults.isEmpty()) {
        return new RerankOutput(fallback(topN, documents.size()), null);
      }

      List<RerankResult> results = new ArrayList<>();
      for (Map<String, Object> r : rawResults) {
        RerankResult item = new RerankResult();
        item.setIndex(toInt(r.get("index")));
        item.setScore(toDouble(r.get("relevance_score")));
        results.add(item);
      }

      log.info("Reranker返回 topN={} scores={}",
          results.size(),
          results.stream().map(r -> String.format("%.4f", r.getScore())).toList());
      return new RerankOutput(results, null);
    } catch (Exception e) {
      log.warn("Reranker unavailable, fallback to original order: {}", e.getMessage());
      return new RerankOutput(fallback(topN, documents.size()), null);
    }
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
