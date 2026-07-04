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
    boolean recorded = false;
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
      HttpResponse<String> response = sendWithRetry(builder.build());
      int sc = response.statusCode();
      if (sc == 429) {
        // 限流重试后仍失败：抛机器码(净化展示为"向量化服务繁忙，请稍后重试")，不泄露 DashScope 原文
        log.warn("VL Embedding 限流(429)重试后仍失败: {}", brief(response.body()));
        throw new BizException("EMBEDDING_RATE_LIMITED");
      }
      if (sc / 100 != 2) {
        log.warn("VL Embedding 非2xx HTTP {}: {}", sc, brief(response.body()));
        throw new IOException("embedding HTTP " + sc);
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
      recorded = true;
      return parseEmbeddings(tree, inputs.size(), properties.getVl().getDimension());
    } catch (BizException e) {
      if (!recorded) {
        recordEmbeddingFailure(start);
      }
      throw e; // 已是机器码(如 EMBEDDING_RATE_LIMITED)
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (!recorded) {
        recordEmbeddingFailure(start);
      }
      throw new BizException("EMBEDDING_CALL_FAILED");
    } catch (IllegalStateException e) {
      if (!recorded) {
        recordEmbeddingFailure(start);
      }
      throw e;
    } catch (Exception e) {
      log.warn("VL Embedding 调用失败", e);
      if (!recorded) {
        recordEmbeddingFailure(start);
      }
      throw new BizException("EMBEDDING_CALL_FAILED");
    }
  }

  /** 计量失败调用：让成功率能反映 429/超时/5xx 等硬失败（token 记 0、成本恒 0）。 */
  private void recordEmbeddingFailure(long start) {
    modelUsageRecorder.record(
        new com.ragforge.modelcenter.ModelUsageEvent(
            properties.getVl().getModel(),
            com.ragforge.modelcenter.Purpose.EMBEDDING,
            0,
            0,
            System.currentTimeMillis() - start,
            false));
  }

  /** 429 / 5xx 指数退避重试（含抖动），缓解大批量入库时打爆嵌入 API 速率配额。 */
  private HttpResponse<String> sendWithRetry(HttpRequest req)
      throws IOException, InterruptedException {
    int maxRetries = 4;
    long backoffMs = 500;
    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    for (int attempt = 0; attempt < maxRetries; attempt++) {
      int sc = resp.statusCode();
      if (sc != 429 && sc / 100 != 5) {
        return resp; // 成功或不可重试
      }
      long wait = Math.min(backoffMs, 8000) + (long) (Math.random() * 250);
      log.info("VL Embedding HTTP {} 第{}次退避重试 {}ms", sc, attempt + 1, wait);
      Thread.sleep(wait);
      backoffMs *= 2;
      resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }
    return resp;
  }

  private static String brief(String body) {
    if (body == null) {
      return "";
    }
    return body.length() > 200 ? body.substring(0, 200) : body;
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
    // parameters.dimension 指定输出维度（qwen3-vl-embedding Matryoshka 截断，默认 1024）。
    return Map.of(
        "model", properties.getVl().getModel(),
        "input", Map.of("contents", contents),
        "parameters", Map.of("dimension", properties.getVl().getDimension()));
  }

  static List<float[]> parseEmbeddings(JsonNode root, int expectedCount, int expectedDim) {
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
      if (vector.length != expectedDim) {
        throw new IllegalStateException(
            "VL Embedding dimension must be " + expectedDim + ", actual=" + vector.length);
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
