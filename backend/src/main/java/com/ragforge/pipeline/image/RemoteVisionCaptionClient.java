package com.ragforge.pipeline.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

@Slf4j
public class RemoteVisionCaptionClient implements VisionCaptionClient {

  private final MultimodalProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final Semaphore semaphore;

  public RemoteVisionCaptionClient(MultimodalProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
            .build();
    this.semaphore = new Semaphore(Math.max(1, properties.getMaxConcurrent()));
  }

  @Override
  public String describe(byte[] imageBytes, String contentType, String filename) {
    if (!StringUtils.hasText(properties.getCaptionEndpoint())) {
      log.warn("Vision caption endpoint is empty, returning fallback caption for {}", filename);
      return "图片文件：" + (StringUtils.hasText(filename) ? filename : "unknown");
    }
    boolean acquired = false;
    try {
      semaphore.acquire();
      acquired = true;
      String body =
          objectMapper.writeValueAsString(
              Map.of(
                  "contentType", contentType,
                  "filename", filename,
                  "imageBase64", Base64.getEncoder().encodeToString(imageBytes)));
      HttpRequest.Builder builder =
          HttpRequest.newBuilder()
              .uri(URI.create(properties.getCaptionEndpoint()))
              .timeout(Duration.ofMillis(properties.getTimeoutMs()))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body));
      if (StringUtils.hasText(properties.getApiKey())) {
        builder.header("Authorization", "Bearer " + properties.getApiKey());
      }
      HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        throw new BizException("VISION_HTTP_" + response.statusCode());
      }
      JsonNode root = objectMapper.readTree(response.body());
      return root.path("description").asText(root.path("data").path("description").asText(""));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BizException("图像描述调用被中断");
    } catch (Exception e) {
      throw new BizException("图像描述调用失败: " + e.getMessage());
    } finally {
      if (acquired) {
        semaphore.release();
      }
    }
  }
}
