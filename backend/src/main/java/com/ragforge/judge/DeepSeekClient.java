package com.ragforge.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragforge.common.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class DeepSeekClient {

  /**
   * V4-Flash miss-price per 1K tokens (CNY). Cache hit is 1/50 of miss; estimate uses miss.
   *
   * @see <a href="https://api-docs.deepseek.com/quick_start/pricing">DeepSeek pricing</a>
   *      (2026-06-23, ¥1/M input miss, ¥2/M output)
   */
  private static final BigDecimal V4_FLASH_INPUT_PRICE_CNY_PER_K_MISS = new BigDecimal("0.001");

  private static final BigDecimal V4_FLASH_OUTPUT_PRICE_CNY_PER_K = new BigDecimal("0.002");

  private final RestTemplate deepseekRestTemplate;
  private final ObjectMapper objectMapper;

  public DeepSeekClient(
      @Qualifier("deepseekRestTemplate") RestTemplate deepseekRestTemplate,
      ObjectMapper objectMapper) {
    this.deepseekRestTemplate = deepseekRestTemplate;
    this.objectMapper = objectMapper;
  }

  @Value("${app.deepseek.api-key:}")
  private String apiKey;

  @Value("${app.deepseek.base-url:https://api.deepseek.com/v1}")
  private String baseUrl;

  @Value("${app.deepseek.model:deepseek-v4-flash}")
  private String model;

  @Value("${app.deepseek.enable-thinking:false}")
  private boolean enableThinking;

  @Value("${app.deepseek.max-tokens:1024}")
  private int maxTokens = 1024;

  @Value("${app.deepseek.temperature:0.0}")
  private double temperature;

  @Value("${app.deepseek.timeout-ms:30000}")
  private int timeoutMs;

  @Value("${app.deepseek.max-retries:3}")
  private int maxRetries;

  @Value("${app.deepseek.retry-backoff-ms:2000}")
  private int retryBackoffMs;

  public ChatResult chat(String systemPrompt, String userPrompt) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new BizException(500, "DeepSeek API key 未配置");
    }
    if (systemPrompt == null || systemPrompt.isBlank() || userPrompt == null || userPrompt.isBlank()) {
      throw new BizException(400, "systemPrompt / userPrompt 不能为空");
    }

    Exception lastError = null;
    for (int attempt = 1; attempt <= Math.max(1, maxRetries); attempt++) {
      long startMs = System.currentTimeMillis();
      try {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        // 避免连接池复用导致响应体截断（运行态 judge 链路曾出现仅读到 "{" 的 JSON 解析失败）
        headers.setConnection("close");
        headers.set("Accept-Encoding", "identity");

        var messages = objectMapper.createArrayNode();
        messages.add(objectMapper.createObjectNode().put("role", "system").put("content", systemPrompt));
        messages.add(objectMapper.createObjectNode().put("role", "user").put("content", userPrompt));

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.set("messages", messages);
        requestBody.put("temperature", temperature);
        // V4 thinking toggle: {"thinking": {"type": "enabled|disabled"}} (default enabled).
        // https://api-docs.deepseek.com/api/create-chat-completion
        requestBody
            .putObject("thinking")
            .put("type", enableThinking ? "enabled" : "disabled");
        requestBody.putObject("response_format").put("type", "json_object");
        requestBody.put("max_tokens", Math.max(1, maxTokens));

        String body = requestBody.toString();
        ResponseEntity<String> response =
            deepseekRestTemplate.postForEntity(
                baseUrl + "/chat/completions", new HttpEntity<>(body, headers), String.class);

        String responseBody = response.getBody();
        if (responseBody == null || responseBody.isBlank()) {
          throw new RuntimeException(
              "DeepSeek response is empty, httpStatus=" + response.getStatusCode());
        }

        JsonNode root;
        try {
          root = objectMapper.readTree(responseBody);
        } catch (Exception parseEx) {
          String preview =
              responseBody.length() <= 200 ? responseBody : responseBody.substring(0, 200) + "...";
          throw new RuntimeException(
              "DeepSeek response JSON parse failed, httpStatus="
                  + response.getStatusCode()
                  + ", bodyLen="
                  + responseBody.length()
                  + ", preview="
                  + preview,
              parseEx);
        }
        JsonNode choice = root.path("choices").path(0);
        String finishReason = choice.path("finish_reason").asText("unknown");
        JsonNode message = choice.path("message");
        String content = message.path("content").asText("").trim();
        if (content.isBlank()) {
          // thinking 未关闭时 reasoning_content 可能有内容；judge 应 disabled，此处仅兜底
          content = message.path("reasoning_content").asText("").trim();
        }
        if (content.isBlank()) {
          throw new RuntimeException("DeepSeek empty content, finish_reason=" + finishReason);
        }
        if ("length".equals(finishReason)) {
          throw new RuntimeException("DeepSeek response truncated, finish_reason=length");
        }
        int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
        int completionTokens = root.path("usage").path("completion_tokens").asInt(0);

        return new ChatResult(
            content,
            promptTokens,
            completionTokens,
            Math.toIntExact(System.currentTimeMillis() - startMs));
      } catch (BizException e) {
        throw e;
      } catch (Exception e) {
        lastError = e;
        log.warn("DeepSeek chat failed on attempt {}: {}", attempt, e.getMessage());
        if (attempt >= Math.max(1, maxRetries)) {
          throw new RuntimeException("DeepSeek chat failed: " + e.getMessage(), e);
        }
        long sleepMs = Math.max(1L, retryBackoffMs * (1L << (attempt - 1)));
        try {
          Thread.sleep(sleepMs);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw new RuntimeException("DeepSeek chat interrupted", ex);
        }
      }
    }

    throw new RuntimeException(
        "DeepSeek chat failed",
        lastError == null ? new RuntimeException("unknown error") : lastError);
  }

  public record ChatResult(
      String content, int promptTokens, int completionTokens, int latencyMs) {

    /**
     * V4-Flash miss price: input ¥1/M, output ¥2/M. Cache hits cost 1/50 of miss price; this
     * estimate uses miss price (conservative).
     *
     * @see <a href="https://api-docs.deepseek.com/quick_start/pricing">DeepSeek pricing</a>
     */
    public BigDecimal estimateCostCny() {
      BigDecimal inputCost =
          BigDecimal.valueOf(promptTokens)
              .multiply(V4_FLASH_INPUT_PRICE_CNY_PER_K_MISS)
              .divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP);
      BigDecimal outputCost =
          BigDecimal.valueOf(completionTokens)
              .multiply(V4_FLASH_OUTPUT_PRICE_CNY_PER_K)
              .divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP);
      return inputCost.add(outputCost);
    }
  }
}
