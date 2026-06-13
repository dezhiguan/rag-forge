package com.ragforge.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.model.dto.LlmGenerateRequest;
import com.ragforge.service.LlmService;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
public class LlmServiceImpl implements LlmService {

  private final RestTemplate llmRestTemplate;
  private final ObjectMapper objectMapper;

  @Value("${app.dashscope.api-key:}")
  private String apiKey;

  @Value("${app.dashscope.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
  private String baseUrl;

  public LlmServiceImpl(
      @Qualifier("llmRestTemplate") RestTemplate llmRestTemplate, ObjectMapper objectMapper) {
    this.llmRestTemplate = llmRestTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public Map<String, Object> generate(LlmGenerateRequest request) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new BizException(500, "DashScope API key 未配置");
    }
    if (request == null) {
      throw new BizException(400, "messages 不能为空");
    }

    String model =
        request.getModel() != null && !request.getModel().isBlank()
            ? request.getModel()
            : "qwen-plus";
    double temperature = request.getTemperature() != null ? request.getTemperature() : 0.3;
    List<Map<String, String>> messages = request.getMessages();
    if (messages == null || messages.isEmpty()) {
      throw new BizException(400, "messages 不能为空");
    }

    try {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setBearerAuth(apiKey);

      var msgArray = objectMapper.createArrayNode();
      for (var msg : messages) {
        msgArray.add(
            objectMapper
                .createObjectNode()
                .put("role", msg.get("role"))
                .put("content", msg.get("content")));
      }

      String requestBody =
          objectMapper
              .createObjectNode()
              .put("model", model)
              .put("temperature", temperature)
              .set("messages", msgArray)
              .toString();

      long start = System.currentTimeMillis();
      HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
      ResponseEntity<String> response =
          llmRestTemplate.postForEntity(baseUrl + "/chat/completions", entity, String.class);
      long totalMs = System.currentTimeMillis() - start;

      String body = response.getBody();
      if (body == null || body.isBlank()) {
        throw new BizException(500, "LLM 返回空响应");
      }

      JsonNode root = objectMapper.readTree(body);
      String content =
          root.path("choices").path(0).path("message").path("content").asText("").trim();
      int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
      int completionTokens = root.path("usage").path("completion_tokens").asInt(0);
      int totalTokens =
          root.path("usage").path("total_tokens").asInt(promptTokens + completionTokens);

      return Map.of(
          "content",
          content,
          "totalMs",
          totalMs,
          "promptTokens",
          promptTokens,
          "completionTokens",
          completionTokens,
          "totalTokens",
          totalTokens,
          "model",
          model);
    } catch (BizException e) {
      throw e;
    } catch (Exception e) {
      log.error("LLM generate failed: {}", e.getMessage(), e);
      throw new BizException(500, "LLM 调用失败: " + e.getMessage());
    }
  }
}
