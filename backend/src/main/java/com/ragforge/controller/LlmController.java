package com.ragforge.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@Slf4j
@RestController
@RequestMapping("/api/v1")
public class LlmController {

  private final RestTemplate llmRestTemplate;
  private final ObjectMapper objectMapper;

  public LlmController(
      @Qualifier("llmRestTemplate") RestTemplate llmRestTemplate, ObjectMapper objectMapper) {
    this.llmRestTemplate = llmRestTemplate;
    this.objectMapper = objectMapper;
  }

  @Value("${app.dashscope.api-key:}")
  private String apiKey;

  @Value("${app.dashscope.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
  private String baseUrl;

  @PostMapping("/llm/generate")
  public Result<Map<String, Object>> generate(@RequestBody LlmRequest request) {
    if (apiKey == null || apiKey.isBlank()) {
      return Result.fail(500, "DashScope API key 未配置");
    }

    String model = request.getModel() != null && !request.getModel().isBlank()
        ? request.getModel() : "qwen-plus";
    double temperature = request.getTemperature() != null ? request.getTemperature() : 0.3;
    List<Map<String, String>> messages = request.getMessages();
    if (messages == null || messages.isEmpty()) {
      return Result.fail(400, "messages 不能为空");
    }

    try {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setBearerAuth(apiKey);

      var msgArray = objectMapper.createArrayNode();
      for (var msg : messages) {
        msgArray.add(objectMapper.createObjectNode()
            .put("role", msg.get("role"))
            .put("content", msg.get("content")));
      }

      String requestBody = objectMapper.createObjectNode()
          .put("model", model)
          .put("temperature", temperature)
          .set("messages", msgArray)
          .toString();

      long start = System.currentTimeMillis();
      HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
      ResponseEntity<String> response = llmRestTemplate.postForEntity(
          baseUrl + "/chat/completions", entity, String.class);
      long totalMs = System.currentTimeMillis() - start;

      String body = response.getBody();
      if (body == null || body.isBlank()) {
        return Result.fail(500, "LLM 返回空响应");
      }

      JsonNode root = objectMapper.readTree(body);
      String content = root.path("choices").path(0).path("message").path("content").asText("").trim();
      int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
      int completionTokens = root.path("usage").path("completion_tokens").asInt(0);
      int totalTokens = root.path("usage").path("total_tokens").asInt(promptTokens + completionTokens);

      Map<String, Object> result = Map.of(
          "content", content,
          "totalMs", totalMs,
          "promptTokens", promptTokens,
          "completionTokens", completionTokens,
          "totalTokens", totalTokens,
          "model", model
      );

      return Result.ok(result);
    } catch (Exception e) {
      log.error("LLM generate failed: {}", e.getMessage(), e);
      return Result.fail(500, "LLM 调用失败: " + e.getMessage());
    }
  }

  public static class LlmRequest {
    private List<Map<String, String>> messages;
    private String model;
    private Double temperature;

    public List<Map<String, String>> getMessages() { return messages; }
    public void setMessages(List<Map<String, String>> messages) { this.messages = messages; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
  }
}
