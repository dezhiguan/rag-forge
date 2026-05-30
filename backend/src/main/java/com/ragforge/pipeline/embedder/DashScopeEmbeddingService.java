package com.ragforge.pipeline.embedder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.config.DashScopeProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DashScopeEmbeddingService implements EmbeddingService {

  /** text-embedding-v4 API limit per request. */
  private static final int MAX_BATCH_SIZE = 10;

  private final DashScopeProperties properties;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Semaphore requestSemaphore;
  private final AtomicLong nextAvailableAtMs = new AtomicLong(0L);

  public DashScopeEmbeddingService(DashScopeProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.requestSemaphore = new Semaphore(Math.max(1, properties.getMaxConcurrentRequests()));
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getTimeout()))
            .build();
  }

  @Override
  public float[] embed(String text) {
    List<float[]> vectors = embedBatch(List.of(text));
    return vectors.get(0);
  }

  @Override
  public List<float[]> embedBatch(List<String> texts) {
    if (texts == null || texts.isEmpty()) {
      return List.of();
    }

    int batchSize = Math.min(properties.getBatchSize(), MAX_BATCH_SIZE);
    if (properties.getBatchSize() > MAX_BATCH_SIZE) {
      log.warn(
          "app.dashscope.batch-size={} exceeds API limit {}, using {}",
          properties.getBatchSize(),
          MAX_BATCH_SIZE,
          MAX_BATCH_SIZE);
    }
    List<float[]> all = new ArrayList<>(texts.size());
    for (List<String> batch : partitionBatches(texts, batchSize)) {
      all.addAll(callEmbeddingApi(batch));
    }
    return all;
  }

  static List<List<String>> partitionBatches(List<String> texts, int batchSize) {
    List<List<String>> batches = new ArrayList<>();
    for (int i = 0; i < texts.size(); i += batchSize) {
      batches.add(texts.subList(i, Math.min(i + batchSize, texts.size())));
    }
    return batches;
  }

  private List<float[]> callEmbeddingApi(List<String> texts) {
    Exception lastError = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      if (attempt > 0) {
        sleepBeforeRetry();
      }
      long start = System.currentTimeMillis();
      boolean acquired = false;
      try {
        requestSemaphore.acquire();
        acquired = true;
        waitForRateLimitSlot();
        List<float[]> vectors = doCall(texts);
        long elapsed = System.currentTimeMillis() - start;
        log.info(
            "DashScope embedding completed in {} ms, batchSize={}", elapsed, texts.size());
        return vectors;
      } catch (Exception e) {
        lastError = e;
        log.warn(
            "DashScope embedding failed (attempt {}): {}", attempt + 1, e.getMessage());
      } finally {
        if (acquired) {
          requestSemaphore.release();
        }
      }
    }
    throw new BizException(
        "Embedding 调用失败："
            + (lastError != null ? lastError.getMessage() : "unknown error"));
  }

  private void waitForRateLimitSlot() {
    long interval = Math.max(0, properties.getMinRequestIntervalMs());
    if (interval <= 0) {
      return;
    }
    while (true) {
      long now = System.currentTimeMillis();
      long expected = nextAvailableAtMs.get();
      long waitMs = expected - now;
      if (waitMs <= 0) {
        if (nextAvailableAtMs.compareAndSet(expected, now + interval)) {
          return;
        }
        continue;
      }
      try {
        Thread.sleep(waitMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new BizException("Embedding 限流等待被中断");
      }
    }
  }

  private List<float[]> doCall(List<String> texts) throws IOException, InterruptedException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", properties.getModel());
    body.put("input", Map.of("texts", texts));
    body.put(
        "parameters",
        Map.of("dimension", properties.getDimension(), "output_type", "dense"));

    String json = objectMapper.writeValueAsString(body);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(DashScopeProperties.EMBEDDING_URL))
            .timeout(Duration.ofMillis(properties.getTimeout()))
            .header("Authorization", "Bearer " + properties.getApiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
    }

    return parseEmbeddings(objectMapper.readTree(response.body()), texts.size());
  }

  static List<float[]> parseEmbeddings(JsonNode root, int expectedCount) {
    JsonNode dataNode = root.path("data");
    if (dataNode.isArray() && !dataNode.isEmpty()) {
      return parseDataArray(dataNode, expectedCount);
    }

    JsonNode embeddingsNode = root.path("output").path("embeddings");
    if (embeddingsNode.isArray() && !embeddingsNode.isEmpty()) {
      return parseOutputEmbeddings(embeddingsNode, expectedCount);
    }

    String message = root.path("message").asText("empty embedding response");
    throw new BizException("Embedding 响应解析失败：" + message);
  }

  private static List<float[]> parseDataArray(JsonNode dataNode, int expectedCount) {
    List<float[]> result = new ArrayList<>(expectedCount);
    for (JsonNode item : dataNode) {
      result.add(toFloatArray(item.path("embedding")));
    }
    if (result.size() != expectedCount) {
      throw new BizException(
          "Embedding 数量不匹配，期望 " + expectedCount + "，实际 " + result.size());
    }
    return result;
  }

  private static List<float[]> parseOutputEmbeddings(JsonNode embeddingsNode, int expectedCount) {
    float[][] ordered = new float[expectedCount][];
    for (JsonNode item : embeddingsNode) {
      int textIndex = item.path("text_index").asInt(-1);
      if (textIndex < 0 || textIndex >= expectedCount) {
        throw new BizException("Embedding text_index 越界: " + textIndex);
      }
      ordered[textIndex] = toFloatArray(item.path("embedding"));
    }
    List<float[]> result = new ArrayList<>(expectedCount);
    for (int i = 0; i < expectedCount; i++) {
      if (ordered[i] == null) {
        throw new BizException("Embedding 缺失 text_index=" + i);
      }
      result.add(ordered[i]);
    }
    return result;
  }

  private static float[] toFloatArray(JsonNode embeddingNode) {
    if (!embeddingNode.isArray()) {
      throw new BizException("Embedding 向量格式错误");
    }
    float[] vector = new float[embeddingNode.size()];
    for (int i = 0; i < embeddingNode.size(); i++) {
      vector[i] = (float) embeddingNode.get(i).asDouble();
    }
    return vector;
  }

  private static void sleepBeforeRetry() {
    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BizException("Embedding 重试被中断");
    }
  }
}
