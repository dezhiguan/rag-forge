package com.ragforge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.model.dto.LlmGenerateRequest;
import com.ragforge.service.LlmService.StreamResult;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class LlmServiceImplTest {

  @Mock private RestTemplate llmRestTemplate;

  private LlmServiceImpl llmService;

  @BeforeEach
  void setUp() {
    llmService = new LlmServiceImpl(llmRestTemplate, new ObjectMapper());
    ReflectionTestUtils.setField(llmService, "apiKey", "sk-test-key");
    ReflectionTestUtils.setField(
        llmService, "baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1");
  }

  @Test
  void generate_missingApiKey_throws500() {
    ReflectionTestUtils.setField(llmService, "apiKey", "  ");

    LlmGenerateRequest request = new LlmGenerateRequest();
    request.setMessages(List.of(Map.of("role", "user", "content", "hi")));

    assertThatThrownBy(() -> llmService.generate(request))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(500);
  }

  @Test
  void generate_nullRequest_throws400() {
    assertThatThrownBy(() -> llmService.generate(null))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(400);
  }

  @Test
  void generate_emptyMessages_throws400() {
    LlmGenerateRequest request = new LlmGenerateRequest();
    request.setMessages(List.of());

    assertThatThrownBy(() -> llmService.generate(request))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(400);
  }

  @Test
  void generate_success_parsesResponse() {
    LlmGenerateRequest request = new LlmGenerateRequest();
    request.setMessages(List.of(Map.of("role", "user", "content", "hello")));
    request.setModel("qwen-turbo");
    request.setTemperature(0.7);

    String body =
        """
        {
          "choices": [{"message": {"content": "world"}}],
          "usage": {"prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5}
        }
        """;
    when(llmRestTemplate.postForEntity(
            eq("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"),
            any(HttpEntity.class),
            eq(String.class)))
        .thenReturn(ResponseEntity.ok(body));

    Map<String, Object> result = llmService.generate(request);

    assertThat(result.get("content")).isEqualTo("world");
    assertThat(result.get("model")).isEqualTo("qwen-turbo");
    assertThat(result.get("promptTokens")).isEqualTo(3);
    assertThat(result.get("completionTokens")).isEqualTo(2);
    assertThat(result.get("totalTokens")).isEqualTo(5);
    assertThat(result.get("totalMs")).isNotNull();
  }

  @Test
  void generate_blankResponse_throws500() {
    LlmGenerateRequest request = new LlmGenerateRequest();
    request.setMessages(List.of(Map.of("role", "user", "content", "hello")));

    when(llmRestTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(String.class)))
        .thenReturn(ResponseEntity.ok(""));

    assertThatThrownBy(() -> llmService.generate(request))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("空响应");
  }

  @Test
  void generate_restTemplateError_wrapsAsBizException() {
    LlmGenerateRequest request = new LlmGenerateRequest();
    request.setMessages(List.of(Map.of("role", "user", "content", "hello")));

    when(llmRestTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(String.class)))
        .thenThrow(new RuntimeException("timeout"));

    assertThatThrownBy(() -> llmService.generate(request))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("LLM 调用失败");
  }

  @Test
  void generate_usesDefaultModelAndTemperature() {
    LlmGenerateRequest request = new LlmGenerateRequest();
    request.setMessages(List.of(Map.of("role", "user", "content", "ping")));

    String body =
        """
        {"choices":[{"message":{"content":"pong"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}
        """;
    when(llmRestTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(String.class)))
        .thenReturn(ResponseEntity.ok(body));

    Map<String, Object> result = llmService.generate(request);

    assertThat(result.get("model")).isEqualTo("qwen-plus");
    assertThat(result.get("content")).isEqualTo("pong");
  }

  @Test
  void streamGenerate_missingApiKey_throws500() {
    ReflectionTestUtils.setField(llmService, "apiKey", "");

    assertThatThrownBy(() -> llmService.streamGenerate(request("hello"), 10, null))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(500);
  }

  @Test
  void streamGenerate_emptyMessages_throws400() {
    LlmGenerateRequest request = new LlmGenerateRequest();
    request.setMessages(List.of());

    assertThatThrownBy(() -> llmService.streamGenerate(request, 10, null))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(400);
  }

  @Test
  void streamGenerate_success_parsesSseDeltasAndUsage() {
    LlmGenerateRequest request = request("hello");
    request.setModel("qwen-max");
    request.setTemperature(0.2);
    List<String> deltas = new ArrayList<>();
    when(llmRestTemplate.execute(any(String.class), eq(HttpMethod.POST), any(), any()))
        .thenAnswer(
            invocation -> {
              org.springframework.web.client.ResponseExtractor<StreamResult> extractor = invocation.getArgument(3);
              ClientHttpResponse response = org.mockito.Mockito.mock(ClientHttpResponse.class);
              String sse =
                  """
                  : keepalive
                  data: {"choices":[{"delta":{"content":"hel"}}]}

                  data: {"choices":[{"delta":{"content":"lo"}}],"usage":{"prompt_tokens":2,"completion_tokens":3}}

                  data: [DONE]

                  """;
              when(response.getBody())
                  .thenReturn(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
              return extractor.extractData(response);
            });

    StreamResult result = llmService.streamGenerate(request, 128, deltas::add);

    assertThat(result.content()).isEqualTo("hello");
    assertThat(result.promptTokens()).isEqualTo(2);
    assertThat(result.completionTokens()).isEqualTo(3);
    assertThat(result.latencyMs()).isNotNegative();
    assertThat(deltas).containsExactly("hel", "lo");
  }

  @Test
  void streamGenerate_nullOrBlankResponse_throws500() {
    when(llmRestTemplate.execute(any(String.class), eq(HttpMethod.POST), any(), any())).thenReturn(null);

    assertThatThrownBy(() -> llmService.streamGenerate(request("hello"), 10, null))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("空响应");
  }

  @Test
  void streamGenerate_restTemplateError_wrapsAsBizException() {
    when(llmRestTemplate.execute(any(String.class), eq(HttpMethod.POST), any(), any()))
        .thenThrow(new RuntimeException("stream down"));

    assertThatThrownBy(() -> llmService.streamGenerate(request("hello"), 10, null))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("LLM 流式调用失败");
  }

  private static LlmGenerateRequest request(String content) {
    LlmGenerateRequest request = new LlmGenerateRequest();
    request.setMessages(List.of(Map.of("role", "user", "content", content)));
    return request;
  }
}
