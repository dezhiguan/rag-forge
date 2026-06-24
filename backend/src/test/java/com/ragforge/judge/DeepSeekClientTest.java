package com.ragforge.judge;

import static org.assertj.core.api.Assertions.assertThat;
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
  void chat_whenEnableThinkingFalse_includesThinkingDisabledInRequestBody() {
    AtomicReference<HttpEntity<String>> requestEntityRef = new AtomicReference<>();
    RestTemplate restTemplate = mock(RestTemplate.class);
    when(restTemplate.postForEntity(anyString(), isA(HttpEntity.class), eq(String.class)))
        .thenAnswer(
            invocation -> {
              requestEntityRef.set(invocation.getArgument(1));
              return ResponseEntity.ok(
                  "{\"choices\":[{\"message\":{\"content\":\"{\\\"score\\\":0.3}\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");
            });

    DeepSeekClient client = new DeepSeekClient(restTemplate, OBJECT_MAPPER);
    ReflectionTestUtils.setField(client, "apiKey", "test-key");
    ReflectionTestUtils.setField(client, "baseUrl", "https://api.deepseek.com/v1");
    ReflectionTestUtils.setField(client, "model", "deepseek-v4-flash");
    ReflectionTestUtils.setField(client, "enableThinking", false);
    ReflectionTestUtils.setField(client, "maxRetries", 1);
    ReflectionTestUtils.setField(client, "retryBackoffMs", 1);

    client.chat("sys", "usr");

    verify(restTemplate)
        .postForEntity(eq("https://api.deepseek.com/v1/chat/completions"), isA(HttpEntity.class), eq(String.class));

    HttpEntity<String> requestEntity = requestEntityRef.get();
    assertThat(requestEntity).isNotNull();
    assertThat(requestEntity.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer test-key");
    assertThat(requestEntity.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(requestEntity.getBody()).contains("\"model\":\"deepseek-v4-flash\"");
    assertThat(requestEntity.getBody()).contains("\"thinking\":{\"type\":\"disabled\"}");
  }
}
