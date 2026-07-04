package com.ragforge.pipeline.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.config.EmbeddingProperties;
import com.ragforge.metrics.RagforgeMetrics;
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
  private final RagforgeMetrics metrics;
  private final com.ragforge.modelcenter.ModelUsageRecorder modelUsageRecorder;
  private final HttpClient httpClient;

  public RemoteOcrClient(
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
            .connectTimeout(Duration.ofMillis(properties.getOcr().getTimeoutMs()))
            .build();
  }

  @Override
  public OcrResult recognize(byte[] imageBytes, String contentType, String filename) {
    if (imageBytes == null || imageBytes.length == 0) {
      return new OcrResult("");
    }
    long start = System.currentTimeMillis();
    boolean recorded = false;
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
      JsonNode tree = objectMapper.readTree(response.body());
      String text = normalizeNoText(extractOcrText(tree));
      int outTokens = estimateTokens(text);
      metrics.recordOcrCall(1, outTokens);
      // 计量并联：input=usage 的 image/input token，output=usage 或文本估算
      long inputTokens = firstUsageLong(tree, "image_tokens", "input_tokens");
      long outputTokens = firstUsageLong(tree, "output_tokens");
      modelUsageRecorder.record(
          new com.ragforge.modelcenter.ModelUsageEvent(
              properties.getOcr().getModel(),
              com.ragforge.modelcenter.Purpose.OCR,
              inputTokens,
              outputTokens > 0 ? outputTokens : outTokens,
              System.currentTimeMillis() - start,
              true));
      recorded = true;
      return new OcrResult(text);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (!recorded) {
        recordOcrFailure(start);
      }
      throw new BizException("OCR 调用被中断");
    } catch (Exception e) {
      if (!recorded) {
        recordOcrFailure(start);
      }
      throw new BizException("OCR 调用失败: " + e.getMessage());
    }
  }

  /** 计量失败调用：让成功率能反映非 2xx / 超时等硬失败（token 记 0、成本恒 0）。 */
  private void recordOcrFailure(long start) {
    modelUsageRecorder.record(
        new com.ragforge.modelcenter.ModelUsageEvent(
            properties.getOcr().getModel(),
            com.ragforge.modelcenter.Purpose.OCR,
            0,
            0,
            System.currentTimeMillis() - start,
            false));
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
                        Map.of("text", "识别图中所有文字"))))),
        // 必须显式指定 text_recognition,否则 qwen-vl-ocr 对密集排版图会走"文本检测"、
        // 只返回检测框坐标(如 236,72,75,421,90)而非识别文字,导致索引里是坐标垃圾、检索不到。
        "parameters",
        Map.of("ocr_options", Map.of("task", "text_recognition")));
  }

  /**
   * qwen-vl-ocr 对无文字图片会返回单字符 "0" 作为"未识别到文字"的信号。若原样入库,会把无意义的
   * "0" 当作图片正文,既污染索引又可能误命中含 "0" 的查询。此处归一化为空串,交由上层改走
   * "[图片：文件名]" 占位符。
   */
  static String normalizeNoText(String text) {
    return text != null && "0".equals(text.trim()) ? "" : text;
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

  private static int estimateTokens(String text) {
    return text == null || text.isBlank() ? 0 : Math.max(1, text.length() / 4);
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
}
