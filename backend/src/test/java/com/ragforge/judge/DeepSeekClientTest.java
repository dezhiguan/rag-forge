package com.ragforge.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

class DeepSeekClientTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void testThinkingFieldFormat_whenDisabled() {
    AtomicReference<HttpEntity<String>> requestEntityRef = new AtomicReference<>();
    RestTemplate restTemplate = mock(RestTemplate.class);
    when(restTemplate.postForEntity(anyString(), isA(HttpEntity.class), eq(String.class)))
        .thenAnswer(
            invocation -> {
              requestEntityRef.set(invocation.getArgument(1));
              return ResponseEntity.ok(
                  "{\"choices\":[{\"message\":{\"content\":\"{\\\"score\\\":0.3}\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");
            });

    DeepSeekClient client = newClient(restTemplate, false);

    client.chat("sys", "usr");

    verify(restTemplate)
        .postForEntity(eq("https://api.deepseek.com/v1/chat/completions"), isA(HttpEntity.class), eq(String.class));

    HttpEntity<String> requestEntity = requestEntityRef.get();
    assertThat(requestEntity).isNotNull();
    assertThat(requestEntity.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer test-key");
    assertThat(requestEntity.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(requestEntity.getBody()).contains("\"model\":\"deepseek-v4-flash\"");
    // https://api-docs.deepseek.com/api/create-chat-completion
    assertThat(requestEntity.getBody()).contains("\"thinking\":{\"type\":\"disabled\"}");
    assertThat(requestEntity.getBody()).contains("\"max_tokens\":1024");
  }

  @Test
  void chat_whenEnableThinkingTrue_includesThinkingEnabledInRequestBody() {
    AtomicReference<HttpEntity<String>> requestEntityRef = new AtomicReference<>();
    RestTemplate restTemplate = mock(RestTemplate.class);
    when(restTemplate.postForEntity(anyString(), isA(HttpEntity.class), eq(String.class)))
        .thenAnswer(
            invocation -> {
              requestEntityRef.set(invocation.getArgument(1));
              return ResponseEntity.ok(
                  "{\"choices\":[{\"message\":{\"content\":\"{\\\"score\\\":0.3}\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");
            });

    DeepSeekClient client = newClient(restTemplate, true);

    client.chat("sys", "usr");

    assertThat(requestEntityRef.get()).isNotNull();
    assertThat(requestEntityRef.get().getBody()).contains("\"thinking\":{\"type\":\"enabled\"}");
  }

  @Test
  void chat_whenFinishReasonLength_throwsExplicitException() {
    RestTemplate restTemplate = mock(RestTemplate.class);
    when(restTemplate.postForEntity(anyString(), isA(HttpEntity.class), eq(String.class)))
        .thenReturn(
            ResponseEntity.ok(
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"score\\\":0.\"},\"finish_reason\":\"length\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}"));

    DeepSeekClient client = newClient(restTemplate, false);

    assertThatThrownBy(() -> client.chat("sys", "usr"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("finish_reason=length");
  }

  @Test
  void chat_whenContentBlank_usesReasoningContentFallback() {
    RestTemplate restTemplate = mock(RestTemplate.class);
    when(restTemplate.postForEntity(anyString(), isA(HttpEntity.class), eq(String.class)))
        .thenReturn(
            ResponseEntity.ok(
                "{\"choices\":[{\"message\":{\"content\":\"\",\"reasoning_content\":\"{\\\"score\\\":0.7}\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}"));

    DeepSeekClient client = newClient(restTemplate, false);

    DeepSeekClient.ChatResult result = client.chat("sys", "usr");
    assertThat(result.content()).isEqualTo("{\"score\":0.7}");
  }

  private static DeepSeekClient newClient(RestTemplate restTemplate, boolean enableThinking) {
    DeepSeekClient client = new DeepSeekClient(restTemplate, OBJECT_MAPPER);
    ReflectionTestUtils.setField(client, "apiKey", "test-key");
    ReflectionTestUtils.setField(client, "baseUrl", "https://api.deepseek.com/v1");
    ReflectionTestUtils.setField(client, "model", "deepseek-v4-flash");
    ReflectionTestUtils.setField(client, "enableThinking", enableThinking);
    ReflectionTestUtils.setField(client, "maxTokens", 1024);
    ReflectionTestUtils.setField(client, "maxRetries", 1);
    ReflectionTestUtils.setField(client, "retryBackoffMs", 1);
    return client;
  }
}
