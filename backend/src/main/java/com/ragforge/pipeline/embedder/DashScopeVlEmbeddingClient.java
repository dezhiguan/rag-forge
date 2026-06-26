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
  private final com.ragforge.modelcenter.ModelUsageRecorder modelUsageRecorder;
  private final HttpClient httpClient;

  public DashScopeVlEmbeddingClient(
      EmbeddingProperties properties,
      ObjectMapper objectMapper,
      RagforgeMetrics metrics,
      com.ragforge.modelcenter.ModelUsageRecorder modelUsageRecorder) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.metrics = metrics;
    this.modelUsageRecorder = modelUsageRecorder;
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
    long start = System.currentTimeMillis();
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
      JsonNode tree = objectMapper.readTree(response.body());
      // 计量并联：优先用响应 usage 的 token；缺失则按文本长度估算（与 OCR 同口径）
      long inputTokens = readUsageTokens(tree, inputs);
      modelUsageRecorder.record(
          new com.ragforge.modelcenter.ModelUsageEvent(
              properties.getVl().getModel(),
              com.ragforge.modelcenter.Purpose.EMBEDDING,
              inputTokens,
              0,
              System.currentTimeMillis() - start,
              true));
      return parseEmbeddings(tree, inputs.size());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BizException("VL Embedding 调用被中断");
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new BizException("VL Embedding 调用失败: " + e.getMessage());
    }
  }

  /** 读取 usage token；缺失则按文本长度估算（图片输入不计文本 token）。 */
  private long readUsageTokens(JsonNode tree, List<EmbeddingInput> inputs) {
    long t = firstUsageLong(tree, "input_tokens", "total_tokens", "text_tokens");
    if (t > 0) {
      return t;
    }
    long est = 0;
    for (EmbeddingInput in : inputs) {
      if (in != null && !in.isImage() && in.getText() != null) {
        est += Math.max(1, in.getText().length() / 4);
      }
    }
    return est;
  }

  private static long firstUsageLong(JsonNode tree, String... fields) {
    JsonNode usage = tree.path("usage");
    JsonNode usageOut = tree.path("output").path("usage");
    for (String f : fields) {
      long v = usage.path(f).asLong(0);
      if (v > 0) {
        return v;
      }
      long v2 = usageOut.path(f).asLong(0);
      if (v2 > 0) {
        return v2;
      }
    }
    return 0;
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
