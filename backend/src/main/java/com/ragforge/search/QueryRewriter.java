package com.ragforge.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.modelcenter.ModelResolver;
import com.ragforge.modelcenter.Purpose;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriter {

  private static final String SYSTEM_PROMPT =
      "你是一个查询改写助手。把用户输入的问题改写成 2-3 个不同角度的检索查询，用于在知识库中检索相关文档。返回纯文本，每行一个查询，不要编号。";

  private final RestTemplate restTemplate;
  private final ObjectMapper objectMapper;
  private final ModelResolver modelResolver;

  @Value("${app.dashscope.api-key:}")
  private String apiKey;

  @Value("${app.dashscope.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
  private String baseUrl;

  @Value("${app.dashscope.rewrite-model:qwen-turbo}")
  private String model;

  @Cacheable(
      value = "queryRewrite",
      key = "#originalQuery",
      unless = "#result == null || #result.size() <= 1")
  public List<String> rewrite(String originalQuery) {
    long start = System.currentTimeMillis();
    List<String> fallback = List.of(originalQuery);
    if (originalQuery == null || originalQuery.isBlank()) {
      log.info("Query rewrite skipped: reason=blank latency={}ms", System.currentTimeMillis() - start);
      return fallback;
    }
    if (apiKey == null || apiKey.isBlank()) {
      log.info("Query rewrite skipped: reason=missing_api_key latency={}ms", System.currentTimeMillis() - start);
      return fallback;
    }

    try {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setBearerAuth(apiKey);

      // 动态解析 REWRITE 生效模型；无可用模型时回退到 yml 默认值（改写本身可优雅降级）
      String effectiveModel = modelResolver.resolveCodeOrDefault(Purpose.REWRITE, model);

      String requestBody =
          objectMapper
              .createObjectNode()
              .put("model", effectiveModel)
              .put("temperature", 0.3)
              .set(
                  "messages",
                  objectMapper
                      .createArrayNode()
                      .add(
                          objectMapper
                              .createObjectNode()
                              .put("role", "system")
                              .put("content", SYSTEM_PROMPT))
                      .add(
                          objectMapper
                              .createObjectNode()
                              .put("role", "user")
                              .put("content", originalQuery)))
              .toString();

      HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
      ResponseEntity<String> response =
          restTemplate.postForEntity(baseUrl + "/chat/completions", entity, String.class);
      String body = response.getBody();
      if (body == null || body.isBlank()) {
        log.info("Query rewrite fallback: reason=empty_response latency={}ms", System.currentTimeMillis() - start);
        return fallback;
      }

      JsonNode root = objectMapper.readTree(body);
      String content =
          root.path("choices").path(0).path("message").path("content").asText("").trim();
      if (content.isBlank()) {
        log.info("Query rewrite fallback: reason=empty_content latency={}ms", System.currentTimeMillis() - start);
        return fallback;
      }

      Set<String> dedup = new LinkedHashSet<>();
      dedup.add(originalQuery.trim());
      for (String line : content.split("\\r?\\n")) {
        String cleaned = line.replaceFirst("^\\s*[-*\\d.、)\\]]+\\s*", "").trim();
        if (!cleaned.isBlank()) {
          dedup.add(cleaned);
        }
      }
      List<String> result = new ArrayList<>(dedup);
      log.info(
          "Query rewrite completed: variantCount={} latency={}ms original=\"{}\" variants={}",
          result.size(),
          System.currentTimeMillis() - start,
          originalQuery,
          result);
      return result;
    } catch (Exception e) {
      log.warn(
          "Query rewrite failed, fallback to original query: latency={}ms err={}",
          System.currentTimeMillis() - start,
          e.getMessage());
      return fallback;
    }
  }
}
