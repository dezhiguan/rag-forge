package com.ragforge.pipeline.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.config.DashScopeProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.util.StringUtils;

public class RemoteImageEmbeddingClient implements ImageEmbeddingClient {

  private static final int DIMENSION = 1024;

  private final MultimodalProperties properties;
  private final DashScopeProperties dashScopeProperties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final Semaphore semaphore;

  public RemoteImageEmbeddingClient(
      MultimodalProperties properties, DashScopeProperties dashScopeProperties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.dashScopeProperties = dashScopeProperties;
    this.objectMapper = objectMapper;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
            .build();
    this.semaphore = new Semaphore(Math.max(1, properties.getMaxConcurrent()));
  }

  @Override
  public float[] embedImage(byte[] imageBytes, String contentType) {
    if (!StringUtils.hasText(dashScopeProperties.getImageEmbeddingEndpoint())) {
      return deterministicVector(Base64.getEncoder().encodeToString(imageBytes), DIMENSION);
    }
    return call(
        Map.of(
            "model",
            dashScopeProperties.getImageEmbeddingModel(),
            "type",
            "image",
            "contentType",
            contentType,
            "imageBase64",
            Base64.getEncoder().encodeToString(imageBytes)));
  }

  @Override
  public float[] embedText(String query) {
    if (!StringUtils.hasText(dashScopeProperties.getImageEmbeddingEndpoint())) {
      return deterministicVector(query == null ? "" : query, DIMENSION);
    }
    return call(
        Map.of(
            "model",
            dashScopeProperties.getImageEmbeddingModel(),
            "type",
            "text",
            "text",
            query == null ? "" : query));
  }

  private float[] call(Map<String, Object> payload) {
    boolean acquired = false;
    try {
      semaphore.acquire();
      acquired = true;
          HttpRequest.Builder builder =
          HttpRequest.newBuilder()
              .uri(URI.create(dashScopeProperties.getImageEmbeddingEndpoint()))
              .timeout(Duration.ofMillis(properties.getTimeoutMs()))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
      if (StringUtils.hasText(dashScopeProperties.getApiKey())) {
        builder.header("Authorization", "Bearer " + dashScopeProperties.getApiKey());
      }
      HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        throw new BizException("IMAGE_EMBED_HTTP_" + response.statusCode());
      }
      return parseVector(objectMapper.readTree(response.body()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BizException("图像 Embedding 调用被中断");
    } catch (Exception e) {
      throw new BizException("图像 Embedding 调用失败: " + e.getMessage());
    } finally {
      if (acquired) {
        semaphore.release();
      }
    }
  }

  static float[] parseVector(JsonNode root) {
    JsonNode node = root.path("embedding");
    if (!node.isArray()) {
      node = root.path("data").path("embedding");
    }
    if (!node.isArray()) {
      node = root.path("output").path("embedding");
    }
    if (!node.isArray()) {
      throw new BizException("图像 Embedding 响应缺少 embedding");
    }
    float[] vector = new float[node.size()];
    for (int i = 0; i < node.size(); i++) {
      vector[i] = (float) node.get(i).asDouble();
    }
    return vector;
  }

  static float[] deterministicVector(String text, int dimension) {
    float[] vector = new float[dimension];
    int hash = text == null ? 0 : text.hashCode();
    for (int i = 0; i < dimension; i++) {
      int mixed = hash ^ (i * 0x9E3779B9);
      vector[i] = ((mixed & 0x7fffffff) % 2000 - 1000) / 1000.0f;
    }
    return vector;
  }
}
