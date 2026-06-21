package com.ragforge.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragforge.common.BizException;
import com.ragforge.model.dto.LlmGenerateRequest;
import com.ragforge.service.LlmService;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
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

  @Override
  public StreamResult streamGenerate(
      LlmGenerateRequest request, Integer maxTokens, Consumer<String> onDelta) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new BizException(500, "DashScope API key 未配置");
    }
    validateMessages(request);
    String model =
        request.getModel() != null && !request.getModel().isBlank()
            ? request.getModel()
            : "qwen-plus";
    double temperature = request.getTemperature() != null ? request.getTemperature() : 0.3;
    try {
      String requestBody = buildChatRequestBody(request.getMessages(), model, temperature, maxTokens, true);
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));
      headers.setBearerAuth(apiKey);
      long start = System.currentTimeMillis();
      RequestCallback requestCallback =
          req -> {
            req.getHeaders().putAll(headers);
            req.getBody().write(requestBody.getBytes(StandardCharsets.UTF_8));
          };
      ResponseExtractor<StreamResult> extractor =
          response -> {
            StringBuilder answer = new StringBuilder();
            int[] promptTokens = {0};
            int[] completionTokens = {0};
            try (BufferedReader reader =
                new BufferedReader(
                    new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
              String line;
              while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                  continue;
                }
                String data = line.substring(5).trim();
                if (data.isBlank() || "[DONE]".equals(data)) {
                  continue;
                }
                JsonNode root = objectMapper.readTree(data);
                String delta = root.path("choices").path(0).path("delta").path("content").asText("");
                if (!delta.isEmpty()) {
                  answer.append(delta);
                  if (onDelta != null) {
                    onDelta.accept(delta);
                  }
                }
                JsonNode usage = root.path("usage");
                if (!usage.isMissingNode()) {
                  promptTokens[0] = usage.path("prompt_tokens").asInt(promptTokens[0]);
                  completionTokens[0] = usage.path("completion_tokens").asInt(completionTokens[0]);
                }
              }
            }
            return new StreamResult(
                answer.toString(),
                promptTokens[0],
                completionTokens[0],
                System.currentTimeMillis() - start);
          };
      StreamResult result =
          llmRestTemplate.execute(baseUrl + "/chat/completions", org.springframework.http.HttpMethod.POST, requestCallback, extractor);
      if (result == null || result.content().isBlank()) {
        throw new BizException(500, "LLM 返回空响应");
      }
      return result;
    } catch (BizException e) {
      throw e;
    } catch (Exception e) {
      log.error("LLM stream generate failed: {}", e.getMessage(), e);
      throw new BizException(500, "LLM 流式调用失败: " + e.getMessage());
    }
  }

  private void validateMessages(LlmGenerateRequest request) {
    if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
      throw new BizException(400, "messages 不能为空");
    }
  }

  private String buildChatRequestBody(
      List<Map<String, String>> messages, String model, double temperature, Integer maxTokens, boolean stream) {
    var msgArray = objectMapper.createArrayNode();
    for (var msg : messages) {
      msgArray.add(
          objectMapper
              .createObjectNode()
              .put("role", msg.get("role"))
              .put("content", msg.get("content")));
    }
    ObjectNode body =
        objectMapper
            .createObjectNode()
            .put("model", model)
            .put("temperature", temperature)
            .put("stream", stream);
    body.set("messages", msgArray);
    if (maxTokens != null && maxTokens > 0) {
      body.put("max_tokens", maxTokens);
    }
    return body.toString();
  }
}
