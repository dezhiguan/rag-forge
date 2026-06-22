package com.ragforge.pipeline.embedder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.config.EmbeddingProperties;
import com.ragforge.metrics.RagforgeMetrics;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class DashScopeVlEmbeddingClient implements VlEmbeddingClient {

  public static final int VL_DIMENSION = 2560;

  private final EmbeddingProperties properties;
  private final ObjectMapper objectMapper;
  private final RagforgeMetrics metrics;
  private final HttpClient httpClient;

  public DashScopeVlEmbeddingClient(
      EmbeddingProperties properties, ObjectMapper objectMapper, RagforgeMetrics metrics) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.metrics = metrics;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getVl().getTimeoutMs()))
            .build();
  }

  @Override
  public List<float[]> embed(List<EmbeddingInput> inputs) {
    if (inputs == null || inputs.isEmpty()) {
      return List.of();
    }
    int batchSize = Math.max(1, Math.min(properties.getVl().getBatchSize(), 10));
    List<float[]> all = new ArrayList<>(inputs.size());
    for (int i = 0; i < inputs.size(); i += batchSize) {
      List<EmbeddingInput> batch = inputs.subList(i, Math.min(i + batchSize, inputs.size()));
      all.addAll(call(batch));
    }
    return all;
  }

  private List<float[]> call(List<EmbeddingInput> inputs) {
    try {
      String body = objectMapper.writeValueAsString(buildPayload(inputs));
      HttpRequest.Builder builder =
          HttpRequest.newBuilder()
              .uri(URI.create(properties.getVl().getEndpoint()))
              .timeout(Duration.ofMillis(properties.getVl().getTimeoutMs()))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body));
      if (StringUtils.hasText(properties.getApiKey())) {
        builder.header("Authorization", "Bearer " + properties.getApiKey());
      }
      HttpResponse<String> response =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
      }
      metrics.recordVlEmbeddingCall(inputs.size());
      metrics.recordVlEmbeddingImageTokens(
          (int) inputs.stream().filter(input -> input != null && input.isImage()).count());
      return parseEmbeddings(objectMapper.readTree(response.body()), inputs.size());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BizException("VL Embedding 调用被中断");
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new BizException("VL Embedding 调用失败: " + e.getMessage());
    }
  }

  private Map<String, Object> buildPayload(List<EmbeddingInput> inputs) {
    List<Map<String, String>> contents = new ArrayList<>(inputs.size());
    for (EmbeddingInput input : inputs) {
      if (input != null && input.isImage()) {
        contents.add(Map.of("image", dataUri(input.getImageBytes(), input.getImageContentType())));
      } else {
        contents.add(Map.of("text", input == null ? "" : value(input.getText())));
      }
    }
    return Map.of("model", properties.getVl().getModel(), "input", Map.of("contents", contents));
  }

  static List<float[]> parseEmbeddings(JsonNode root, int expectedCount) {
    JsonNode embeddings = root.path("output").path("embeddings");
    if (!embeddings.isArray()) {
      throw new IllegalStateException("VL Embedding 响应缺少 output.embeddings");
    }

    List<float[]> ordered = new ArrayList<>(Collections.nCopies(expectedCount, null));
    for (JsonNode item : embeddings) {
      int index = item.path("index").asInt(-1);
      if (index < 0 || index >= expectedCount) {
        throw new IllegalStateException("VL Embedding index 越界: " + index);
      }
      String type = item.path("type").asText();
      if (!"vl".equals(type)) {
        throw new IllegalStateException("VL Embedding type must be vl, actual=" + type);
      }
      float[] vector = toFloatArray(item.path("embedding"));
      if (vector.length != VL_DIMENSION) {
        throw new IllegalStateException(
            "VL Embedding dimension must be 2560, actual=" + vector.length);
      }
      ordered.set(index, vector);
    }
    for (int i = 0; i < expectedCount; i++) {
      if (ordered.get(i) == null) {
        throw new IllegalStateException("VL Embedding missing index=" + i);
      }
    }
    return ordered;
  }

  private static float[] toFloatArray(JsonNode node) {
    if (!node.isArray()) {
      throw new IllegalStateException("VL Embedding 向量格式错误");
    }
    float[] vector = new float[node.size()];
    for (int i = 0; i < node.size(); i++) {
      vector[i] = (float) node.get(i).asDouble();
    }
    return vector;
  }

  private static String dataUri(byte[] bytes, String contentType) {
    String type = StringUtils.hasText(contentType) ? contentType : "image/png";
    return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes);
  }

  private static String value(String value) {
    return value == null ? "" : value;
  }
}
