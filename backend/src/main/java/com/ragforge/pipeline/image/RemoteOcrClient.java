package com.ragforge.pipeline.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.config.EmbeddingProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

@Slf4j
public class RemoteOcrClient implements OcrClient {

  private final EmbeddingProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public RemoteOcrClient(EmbeddingProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getOcr().getTimeoutMs()))
            .build();
  }

  @Override
  public OcrResult recognize(byte[] imageBytes, String contentType, String filename) {
    if (imageBytes == null || imageBytes.length == 0) {
      return new OcrResult("");
    }
    try {
      String body = objectMapper.writeValueAsString(buildPayload(imageBytes, contentType));
      HttpRequest.Builder builder =
          HttpRequest.newBuilder()
              .uri(URI.create(properties.getOcr().getEndpoint()))
              .timeout(Duration.ofMillis(properties.getOcr().getTimeoutMs()))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body));
      if (StringUtils.hasText(properties.getApiKey())) {
        builder.header("Authorization", "Bearer " + properties.getApiKey());
      }
      HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        throw new BizException("OCR_HTTP_" + response.statusCode());
      }
      return new OcrResult(extractOcrText(objectMapper.readTree(response.body())));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BizException("OCR 调用被中断");
    } catch (Exception e) {
      throw new BizException("OCR 调用失败: " + e.getMessage());
    }
  }

  private Map<String, Object> buildPayload(byte[] imageBytes, String contentType) {
    return Map.of(
        "model",
        properties.getOcr().getModel(),
        "input",
        Map.of(
            "messages",
            List.of(
                Map.of(
                    "role",
                    "user",
                    "content",
                    List.of(
                        Map.of("image", dataUri(imageBytes, contentType)),
                        Map.of("text", "识别图中所有文字"))))));
  }

  static String extractOcrText(JsonNode root) {
    JsonNode content =
        root.path("output")
            .path("choices")
            .path(0)
            .path("message")
            .path("content")
            .path(0);
    String processedText = content.path("ocr_result").path("processed_text").asText("");
    if (StringUtils.hasText(processedText)) {
      return processedText;
    }
    String text = content.path("text").asText("");
    if (StringUtils.hasText(text)) {
      return text;
    }

    String oldText = root.path("text").asText("");
    if (StringUtils.hasText(oldText)) {
      return oldText;
    }
    oldText = root.path("data").path("text").asText("");
    if (StringUtils.hasText(oldText)) {
      return oldText;
    }
    return root.isTextual() ? root.asText("") : "";
  }

  private static String dataUri(byte[] imageBytes, String contentType) {
    String type = StringUtils.hasText(contentType) ? contentType : "image/png";
    return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
  }
}
