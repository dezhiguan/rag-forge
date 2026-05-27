package com.ragforge.search;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
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

  @Value("${reranker.base-url:http://localhost:8001}")
  private String baseUrl;

  public RerankOutput rerank(String query, List<String> documents, int topN) {
    if (documents == null || documents.isEmpty()) {
      return new RerankOutput(List.of(), null);
    }
    try {
      RerankRequest request = new RerankRequest();
      request.setQuery(query);
      request.setDocuments(documents);
      request.setTopN(topN);

      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      HttpEntity<RerankRequest> entity = new HttpEntity<>(request, headers);

      ResponseEntity<RerankResponse> response =
          restTemplate.postForEntity(baseUrl + "/rerank", entity, RerankResponse.class);
      if (response.getBody() == null || response.getBody().getResults() == null) {
        return new RerankOutput(fallback(topN, documents.size()), null);
      }
      return new RerankOutput(response.getBody().getResults(), toLong(response.getBody().getLatencyMs()));
    } catch (Exception e) {
      log.warn("Reranker unavailable, fallback to original order: {}", e.getMessage());
      return new RerankOutput(fallback(topN, documents.size()), null);
    }
  }

  private static Long toLong(Double latencyMs) {
    if (latencyMs == null) {
      return null;
    }
    return Math.round(latencyMs);
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
  public static class RerankRequest {
    private String query;
    private List<String> documents;

    @JsonProperty("top_n")
    private int topN;
  }

  @Data
  public static class RerankResult {
    private int index;
    private double score;
  }

  @Data
  public static class RerankResponse {
    private List<RerankResult> results;

    @JsonProperty("latency_ms")
    private Double latencyMs;
  }

  @Data
  @RequiredArgsConstructor
  public static class RerankOutput {
    private final List<RerankResult> results;
    private final Long latencyMs;
  }
}
