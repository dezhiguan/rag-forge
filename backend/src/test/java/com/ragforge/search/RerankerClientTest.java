package com.ragforge.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.ragforge.search.RerankerClient.RerankOutput;
import com.ragforge.search.RerankerClient.RerankResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class RerankerClientTest {

  @Mock private RestTemplate restTemplate;

  private RerankerClient rerankerClient;

  @BeforeEach
  void setUp() {
    rerankerClient = new RerankerClient(restTemplate);
    ReflectionTestUtils.setField(rerankerClient, "apiKey", "test-key");
    ReflectionTestUtils.setField(
        rerankerClient, "dashscopeBaseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1");
    ReflectionTestUtils.setField(rerankerClient, "model", "gte-rerank-v2");
  }

  @Test
  void emptyDocuments_returnsEmptyWithoutCallingApi() {
    RerankOutput output = rerankerClient.rerank("query", List.of(), 3);

    assertThat(output.getResults()).isEmpty();
    assertThat(output.getLatencyMs()).isZero();
  }

  @Test
  void apiException_fallsBackToOriginalOrder() {
    when(restTemplate.postForEntity(any(String.class), any(), eq(Map.class)))
        .thenThrow(new RuntimeException("network error"));

    RerankOutput output = rerankerClient.rerank("query", List.of("a", "b", "c"), 2);

    assertThat(output.getResults()).hasSize(2);
    assertThat(output.getResults().get(0).getIndex()).isZero();
    assertThat(output.getResults().get(1).getIndex()).isOne();
    assertThat(output.getResults().get(0).getScore()).isGreaterThan(output.getResults().get(1).getScore());
  }

  @Test
  void successfulResponse_parsesResults() {
    Map<String, Object> body =
        Map.of(
            "output",
            Map.of(
                "results",
                List.of(
                    Map.of("index", 1, "relevance_score", 0.95),
                    Map.of("index", 0, "relevance_score", 0.80))));
    when(restTemplate.postForEntity(any(String.class), any(), eq(Map.class)))
        .thenReturn(ResponseEntity.ok(body));

    RerankOutput output = rerankerClient.rerank("query", List.of("doc-a", "doc-b"), 2);

    assertThat(output.getResults()).hasSize(2);
    RerankResult first = output.getResults().get(0);
    assertThat(first.getIndex()).isEqualTo(1);
    assertThat(first.getScore()).isEqualTo(0.95);
    assertThat(output.getLatencyMs()).isNotNull();
  }
}
