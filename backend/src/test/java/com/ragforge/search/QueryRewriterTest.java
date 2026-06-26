package com.ragforge.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class QueryRewriterTest {

  @Mock private RestTemplate restTemplate;
  @Mock private com.ragforge.modelcenter.ModelResolver modelResolver;
  @Mock private com.ragforge.modelcenter.ModelUsageRecorder modelUsageRecorder;

  private QueryRewriter queryRewriter;

  @BeforeEach
  void setUp() {
    queryRewriter = new QueryRewriter(restTemplate, new ObjectMapper(), modelResolver, modelUsageRecorder);
    org.mockito.Mockito.lenient()
        .when(modelResolver.resolveCodeOrDefault(any(), any()))
        .thenAnswer(inv -> inv.getArgument(1));
    ReflectionTestUtils.setField(queryRewriter, "apiKey", "test-key");
    ReflectionTestUtils.setField(
        queryRewriter, "baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1");
    ReflectionTestUtils.setField(queryRewriter, "model", "qwen-turbo");
  }

  @Test
  void blankQuery_returnsOriginalWithoutApiCall() {
    List<String> result = queryRewriter.rewrite("   ");

    assertThat(result).containsExactly("   ");
    verify(restTemplate, never()).postForEntity(any(String.class), any(), eq(String.class));
  }

  @Test
  void missingApiKey_returnsOriginalWithoutApiCall() {
    ReflectionTestUtils.setField(queryRewriter, "apiKey", "");

    List<String> result = queryRewriter.rewrite("Java 后端开发");

    assertThat(result).containsExactly("Java 后端开发");
    verify(restTemplate, never()).postForEntity(any(String.class), any(), eq(String.class));
  }

  @Test
  void successfulResponse_parsesMultipleLinesAndDedups() {
    String responseBody =
        """
        {
          "choices": [
            {
              "message": {
                "content": "1. Spring Boot 面试题\\n2. Java 后端架构\\nSpring Boot 面试题"
              }
            }
          ]
        }
        """;
    when(restTemplate.postForEntity(any(String.class), any(), eq(String.class)))
        .thenReturn(ResponseEntity.ok(responseBody));

    List<String> result = queryRewriter.rewrite("Java 后端开发");

    assertThat(result).containsExactly("Java 后端开发", "Spring Boot 面试题", "Java 后端架构");
  }

  @Test
  void apiException_fallsBackToOriginalQuery() {
    when(restTemplate.postForEntity(any(String.class), any(), eq(String.class)))
        .thenThrow(new RuntimeException("network error"));

    List<String> result = queryRewriter.rewrite("Java 后端开发");

    assertThat(result).containsExactly("Java 后端开发");
  }

  @Test
  void emptyResponseBody_fallsBackToOriginalQuery() {
    when(restTemplate.postForEntity(any(String.class), any(), eq(String.class)))
        .thenReturn(ResponseEntity.ok(""));

    List<String> result = queryRewriter.rewrite("分布式系统");

    assertThat(result).containsExactly("分布式系统");
  }
}
