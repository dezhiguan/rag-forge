package com.ragforge.pipeline.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.config.EmbeddingProperties;
import com.ragforge.metrics.RagforgeMetrics;
import com.ragforge.modelcenter.ModelUsageEvent;
import com.ragforge.modelcenter.ModelUsageRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class RemoteOcrClientTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void extractOcrText_prefersProcessedText() throws Exception {
    String json =
        """
        {"output":{"choices":[{"message":{"content":[
          {"ocr_result":{"processed_text":"首选文字"},"text":"兜底文字"}
        ]}}]}}
        """;

    assertThat(RemoteOcrClient.extractOcrText(objectMapper.readTree(json))).isEqualTo("首选文字");
  }

  @Test
  void extractOcrText_fallsBackToText() throws Exception {
    String json =
        """
        {"output":{"choices":[{"message":{"content":[
          {"text":"兜底文字"}
        ]}}]}}
        """;

    assertThat(RemoteOcrClient.extractOcrText(objectMapper.readTree(json))).isEqualTo("兜底文字");
  }

  @Test
  void extractOcrText_supportsOldFlatFormat() throws Exception {
    assertThat(RemoteOcrClient.extractOcrText(objectMapper.readTree("{\"data\":{\"text\":\"旧格式\"}}")))
        .isEqualTo("旧格式");
  }

  @Test
  void extractOcrText_supportsOldRootTextAndTextualResponse() throws Exception {
    assertThat(RemoteOcrClient.extractOcrText(objectMapper.readTree("{\"text\":\"根节点文字\"}")))
        .isEqualTo("根节点文字");
    assertThat(RemoteOcrClient.extractOcrText(objectMapper.readTree("\"纯文本\""))).isEqualTo("纯文本");
  }

  @Test
  void recognize_emptyImageReturnsEmptyResultWithoutRemoteCall() {
    RemoteOcrClient client =
        new RemoteOcrClient(
            new EmbeddingProperties(),
            objectMapper,
            new RagforgeMetrics(new SimpleMeterRegistry()),
            mock(ModelUsageRecorder.class));

    assertThat(client.recognize(null, "image/png", "empty.png").getText()).isEmpty();
    assertThat(client.recognize(new byte[0], "image/png", "empty.png").getText()).isEmpty();
  }

  @Test
  void recognize_successBuildsRequestRecordsUsageAndReturnsText() throws Exception {
    EmbeddingProperties properties = properties();
    properties.setApiKey("sk-test");
    ModelUsageRecorder recorder = mock(ModelUsageRecorder.class);
    RemoteOcrClient client =
        new RemoteOcrClient(
            properties, objectMapper, new RagforgeMetrics(new SimpleMeterRegistry()), recorder);
    HttpClient httpClient = mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body())
        .thenReturn(
            """
            {"output":{"choices":[{"message":{"content":[{"text":"识别结果"}]}}],
              "usage":{"input_tokens":12,"output_tokens":3}}}
            """);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    ReflectionTestUtils.setField(client, "httpClient", httpClient);

    OcrResult result = client.recognize(new byte[] {1, 2, 3}, "", "a.png");

    assertThat(result.getText()).isEqualTo("识别结果");
    ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
    assertThat(requestCaptor.getValue().headers().firstValue("Authorization"))
        .contains("Bearer sk-test");
    ArgumentCaptor<ModelUsageEvent> eventCaptor = ArgumentCaptor.forClass(ModelUsageEvent.class);
    verify(recorder).record(eventCaptor.capture());
    assertThat(eventCaptor.getValue().inputTokens()).isEqualTo(12);
    assertThat(eventCaptor.getValue().outputTokens()).isEqualTo(3);
  }

  @Test
  void recognize_non2xxResponseWrapsAsBizException() throws Exception {
    RemoteOcrClient client =
        new RemoteOcrClient(
            properties(), objectMapper, new RagforgeMetrics(new SimpleMeterRegistry()), mock(ModelUsageRecorder.class));
    HttpClient httpClient = mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(503);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    ReflectionTestUtils.setField(client, "httpClient", httpClient);

    assertThatThrownBy(() -> client.recognize(new byte[] {1}, "image/png", "a.png"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("OCR_HTTP_503");
  }

  @Test
  void recognize_interruptedRestoresInterruptFlagAndThrowsBizException() throws Exception {
    RemoteOcrClient client =
        new RemoteOcrClient(
            properties(), objectMapper, new RagforgeMetrics(new SimpleMeterRegistry()), mock(ModelUsageRecorder.class));
    HttpClient httpClient = mock(HttpClient.class);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new InterruptedException("stop"));
    ReflectionTestUtils.setField(client, "httpClient", httpClient);

    assertThatThrownBy(() -> client.recognize(new byte[] {1}, "image/png", "a.png"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("OCR 调用被中断");
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    Thread.interrupted();
  }

  @Test
  void normalizeNoText_treatsBareZeroAsEmpty() {
    // qwen-vl-ocr 无文字图返回 "0",应归一化为空,让上层走图片占位符。
    assertThat(RemoteOcrClient.normalizeNoText("0")).isEmpty();
    assertThat(RemoteOcrClient.normalizeNoText("  0 ")).isEmpty();
    assertThat(RemoteOcrClient.normalizeNoText("2024 年营收")).isEqualTo("2024 年营收");
    assertThat(RemoteOcrClient.normalizeNoText("100")).isEqualTo("100");
  }

  private static EmbeddingProperties properties() {
    EmbeddingProperties properties = new EmbeddingProperties();
    properties.getOcr().setEndpoint("http://ocr.example.test/v1");
    properties.getOcr().setModel("qwen-vl-ocr");
    properties.getOcr().setTimeoutMs(1000);
    return properties;
  }
}
