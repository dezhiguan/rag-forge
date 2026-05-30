package com.ragforge.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

  @Value("${app.dashscope.api-key:}")
  private String apiKey;

  @Value("${app.dashscope.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
  private String baseUrl;

  @Value("${app.dashscope.rewrite-model:qwen-turbo}")
  private String model;

  public List<String> rewrite(String originalQuery) {
    List<String> fallback = List.of(originalQuery);
    if (originalQuery == null || originalQuery.isBlank()) {
      return fallback;
    }
    if (apiKey == null || apiKey.isBlank()) {
      return fallback;
    }

    try {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setBearerAuth(apiKey);

      String requestBody =
          objectMapper
              .createObjectNode()
              .put("model", model)
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
        return fallback;
      }

      JsonNode root = objectMapper.readTree(body);
      String content =
          root.path("choices").path(0).path("message").path("content").asText("").trim();
      if (content.isBlank()) {
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
      log.info("Query改写成功 original=\"{}\" variants={}", originalQuery, result);
      return result;
    } catch (Exception e) {
      log.warn("Query rewrite failed, fallback to original query: {}", e.getMessage());
      return fallback;
    }
  }
}
