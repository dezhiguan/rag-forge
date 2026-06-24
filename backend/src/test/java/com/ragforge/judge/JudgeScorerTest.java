package com.ragforge.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.answer.AnswerModels.Citation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpServerErrorException;

class JudgeScorerTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void faithfulness_typical_returnsParsedScoreAndIssues() {
    var client = mock(DeepSeekClient.class);
    when(client.chat(any(), any()))
        .thenReturn(
            new DeepSeekClient.ChatResult(
                "{\"score\":0.90,\"reasoning\":\"证据充分\",\"hallucinated_claims\":[\"无明显幻觉\"]}",
                9,
                5,
                120));

    DefaultJudgeScorer scorer = new DefaultJudgeScorer(client, new JudgePromptLibrary(), OBJECT_MAPPER);
    JudgeScore score = scorer.score(baseContext(), ScoreDimension.FAITHFULNESS);

    assertThat(score.isSuccess()).isTrue();
    assertThat(score.getDimension()).isEqualTo(ScoreDimension.FAITHFULNESS);
    assertThat(score.getScore()).isEqualTo(new BigDecimal("0.9000"));
    assertThat(score.getIssues()).containsExactly("无明显幻觉");
  }

  @Test
  void faithfulness_boundary_withoutClaims() {
    var client = mock(DeepSeekClient.class);
    when(client.chat(any(), any()))
        .thenReturn(
            new DeepSeekClient.ChatResult(
                "{\"score\":0.0,\"reasoning\":\"与检索无关\",\"hallucinated_claims\":[]}",
                4,
                1,
                30));

    DefaultJudgeScorer scorer = new DefaultJudgeScorer(client, new JudgePromptLibrary(), OBJECT_MAPPER);
    JudgeScore score = scorer.score(baseContext(), ScoreDimension.FAITHFULNESS);

    assertThat(score.isSuccess()).isTrue();
    assertThat(score.getScore()).isEqualTo(new BigDecimal("0.0000"));
    assertThat(score.getIssues()).isEmpty();
  }

  @Test
  void contextPrecision_typical_parsesChunkRelevance() {
    var client = mock(DeepSeekClient.class);
    when(client.chat(any(), any()))
        .thenReturn(
            new DeepSeekClient.ChatResult(
                "{\"score\":0.66,\"reasoning\":\"部分 chunk 无法支持答案\",\"chunk_relevance\":[{\"chunk_id\":101,\"relevant\":true},{\"chunk_id\":102,\"relevant\":false}]}",
                12,
                4,
                160));

    DefaultJudgeScorer scorer = new DefaultJudgeScorer(client, new JudgePromptLibrary(), OBJECT_MAPPER);
    JudgeScore score = scorer.score(baseContext(), ScoreDimension.CONTEXT_PRECISION);

    assertThat(score.isSuccess()).isTrue();
    assertThat(score.getIssues()).containsExactly("chunk_id=102 relevant=false");
  }

  @Test
  void contextPrecision_boundary_allIrrelevant() {
    var client = mock(DeepSeekClient.class);
    when(client.chat(any(), any()))
        .thenReturn(
            new DeepSeekClient.ChatResult(
                "{\"score\":0.10,\"reasoning\":\"Top-K 不相关\",\"chunk_relevance\":[{\"chunk_id\":101,\"relevant\":false},{\"chunk_id\":102,\"relevant\":false}]}",
                8,
                2,
                60));

    DefaultJudgeScorer scorer = new DefaultJudgeScorer(client, new JudgePromptLibrary(), OBJECT_MAPPER);
    JudgeScore score = scorer.score(baseContext(), ScoreDimension.CONTEXT_PRECISION);

    assertThat(score.isSuccess()).isTrue();
    assertThat(score.getIssues()).containsExactlyInAnyOrder(
        "chunk_id=101 relevant=false",
        "chunk_id=102 relevant=false");
  }

  @Test
  void answerRelevance_typical_offTopicParts() {
    var client = mock(DeepSeekClient.class);
    when(client.chat(any(), any()))
        .thenReturn(
            new DeepSeekClient.ChatResult(
                "{\"score\":0.75,\"reasoning\":\"聚焦 query，少量补充\",\"off_topic_parts\":[\"补充了历史背景\"]}",
                6,
                3,
                80));

    DefaultJudgeScorer scorer = new DefaultJudgeScorer(client, new JudgePromptLibrary(), OBJECT_MAPPER);
    JudgeScore score = scorer.score(baseContext(), ScoreDimension.ANSWER_RELEVANCE);

    assertThat(score.isSuccess()).isTrue();
    assertThat(score.getIssues()).containsExactly("补充了历史背景");
  }

  @Test
  void answerRelevance_boundary_noOffTopic() {
    var client = mock(DeepSeekClient.class);
    when(client.chat(any(), any()))
        .thenReturn(
            new DeepSeekClient.ChatResult(
                "{\"score\":1.0,\"reasoning\":\"完全回应问题\",\"off_topic_parts\":[]}",
                6,
                3,
                80));

    DefaultJudgeScorer scorer = new DefaultJudgeScorer(client, new JudgePromptLibrary(), OBJECT_MAPPER);
    JudgeScore score = scorer.score(baseContext(), ScoreDimension.ANSWER_RELEVANCE);

    assertThat(score.isSuccess()).isTrue();
    assertThat(score.getIssues()).isEmpty();
  }

  @Test
  void composite_typical_parsesOverallScore() {
    var client = mock(DeepSeekClient.class);
    when(client.chat(any(), any()))
        .thenReturn(new DeepSeekClient.ChatResult("{\"score\":0.95,\"reasoning\":\"good\",\"hallucinated_claims\":[]}", 10, 4, 60))
        .thenReturn(new DeepSeekClient.ChatResult("{\"score\":0.80,\"reasoning\":\"precision\",\"chunk_relevance\":[{\"chunk_id\":101,\"relevant\":true}]}", 10, 4, 60))
        .thenReturn(new DeepSeekClient.ChatResult("{\"score\":0.90,\"reasoning\":\"relevant\",\"off_topic_parts\":[]}", 10, 4, 60))
        .thenReturn(
            new DeepSeekClient.ChatResult(
                "{\"overall_score\":0.85,\"reasoning\":\"检索覆盖与表达较好\",\"bottleneck\":\"RETRIEVAL\",\"improvements\":[\"优化召回阈值\",\"补充样例\"]}",
                10,
                4,
                60));

    DefaultJudgeScorer scorer = new DefaultJudgeScorer(client, new JudgePromptLibrary(), OBJECT_MAPPER);
    JudgeScore score = scorer.score(baseContext(), ScoreDimension.COMPOSITE);

    assertThat(score.isSuccess()).isTrue();
    assertThat(score.getScore()).isEqualTo(new BigDecimal("0.8500"));
    assertThat(score.getIssues()).contains("bottleneck=RETRIEVAL", "优化召回阈值");
  }

  @Test
  void composite_boundary_lowScore() {
    var client = mock(DeepSeekClient.class);
    when(client.chat(any(), any()))
        .thenReturn(new DeepSeekClient.ChatResult("{\"score\":0.05,\"reasoning\":\"bad\",\"hallucinated_claims\":[]}", 10, 4, 60))
        .thenReturn(new DeepSeekClient.ChatResult("{\"score\":0.10,\"reasoning\":\"bad\",\"chunk_relevance\":[]}", 10, 4, 60))
        .thenReturn(new DeepSeekClient.ChatResult("{\"score\":0.15,\"reasoning\":\"bad\",\"off_topic_parts\":[\"离题\"]}", 10, 4, 60))
        .thenReturn(
            new DeepSeekClient.ChatResult(
                "{\"overall_score\":0.05,\"reasoning\":\"整体较差\",\"bottleneck\":\"GENERATION\",\"improvements\":[\"严格引用\"]}",
                10,
                4,
                60));

    DefaultJudgeScorer scorer = new DefaultJudgeScorer(client, new JudgePromptLibrary(), OBJECT_MAPPER);
    JudgeScore score = scorer.score(baseContext(), ScoreDimension.COMPOSITE);

    assertThat(score.isSuccess()).isTrue();
    assertThat(score.getScore()).isEqualTo(new BigDecimal("0.0500"));
    assertThat(score.getIssues()).contains("bottleneck=GENERATION", "严格引用");
  }

  @Test
  void scoreWithRetry_marksUnstableWhenDivergenceLarge() {
    var client = mock(DeepSeekClient.class);
    when(client.chat(any(), any()))
        .thenReturn(
            new DeepSeekClient.ChatResult(
                "{\"score\":0.95,\"reasoning\":\"run1\",\"hallucinated_claims\":[]}",
                1,
                1,
                20))
        .thenReturn(
            new DeepSeekClient.ChatResult(
                "{\"score\":0.60,\"reasoning\":\"run2\",\"hallucinated_claims\":[]}",
                1,
                1,
                20));

    DefaultJudgeScorer scorer = new DefaultJudgeScorer(client, new JudgePromptLibrary(), OBJECT_MAPPER);
    JudgeScore score = scorer.scoreWithRetry(baseContext(), ScoreDimension.FAITHFULNESS, 2);

    assertThat(score.isSuccess()).isTrue();
    assertThat(score.isStable()).isFalse();
    assertThat(score.getScore()).isEqualTo(new BigDecimal("0.7750"));
  }

  @Test
  void deepSeekClient_postsJsonObjectRequestAndParsesResult() {
    AtomicReference<HttpEntity<String>> requestEntityRef = new AtomicReference<>();
    RestTemplate restTemplate = mock(RestTemplate.class);
    when(restTemplate.postForEntity(anyString(), isA(HttpEntity.class), eq(String.class)))
        .thenAnswer(
            invocation -> {
              requestEntityRef.set(invocation.getArgument(1));
              return ResponseEntity.ok(
                  "{\"choices\":[{\"message\":{\"content\":\"{\\\"score\\\":0.3,\\\"reasoning\\\":\\\"ok\\\",\\\"hallucinated_claims\\\":[]}\"}}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20}}");
            });

    DeepSeekClient client = new DeepSeekClient(restTemplate, OBJECT_MAPPER);
    ReflectionTestUtils.setField(client, "apiKey", "test-key");
    ReflectionTestUtils.setField(client, "baseUrl", "https://api.deepseek.com/v1");
    ReflectionTestUtils.setField(client, "model", "deepseek-v4-flash");
    ReflectionTestUtils.setField(client, "enableThinking", false);
    ReflectionTestUtils.setField(client, "maxTokens", 1024);
    ReflectionTestUtils.setField(client, "maxRetries", 1);
    ReflectionTestUtils.setField(client, "retryBackoffMs", 1);

    DeepSeekClient.ChatResult result = client.chat("sys", "usr");
    assertThat(result.content())
        .isEqualTo("{\"score\":0.3,\"reasoning\":\"ok\",\"hallucinated_claims\":[]}");
    assertThat(result.promptTokens()).isEqualTo(100);
    assertThat(result.completionTokens()).isEqualTo(20);
    assertThat(result.estimateCostCny()).isEqualTo(new BigDecimal("0.0001"));

    verify(restTemplate).postForEntity(eq("https://api.deepseek.com/v1/chat/completions"), isA(HttpEntity.class), eq(String.class));

    HttpEntity<String> requestEntity = requestEntityRef.get();
    assertThat(requestEntity).isNotNull();
    assertThat(requestEntity.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer test-key");
    assertThat(requestEntity.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(requestEntity.getBody()).contains("\"response_format\":{\"type\":\"json_object\"}");
    assertThat(requestEntity.getBody()).contains("\"temperature\":0.0");
    assertThat(requestEntity.getBody()).contains("\"thinking\":{\"type\":\"disabled\"}");
  }

  @Test
  void deepSeekClient_usesConfiguredTemperatureInRequestBody() {
    AtomicReference<HttpEntity<String>> requestEntityRef = new AtomicReference<>();
    RestTemplate restTemplate = mock(RestTemplate.class);
    when(restTemplate.postForEntity(anyString(), isA(HttpEntity.class), eq(String.class)))
        .thenAnswer(
            invocation -> {
              requestEntityRef.set(invocation.getArgument(1));
              return ResponseEntity.ok(
                  "{\"choices\":[{\"message\":{\"content\":\"{\\\"score\\\":0.5,\\\"reasoning\\\":\\\"ok\\\"}\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");
            });

    DeepSeekClient client = new DeepSeekClient(restTemplate, OBJECT_MAPPER);
    ReflectionTestUtils.setField(client, "apiKey", "test-key");
    ReflectionTestUtils.setField(client, "baseUrl", "https://api.deepseek.com/v1");
    ReflectionTestUtils.setField(client, "model", "deepseek-v4-flash");
    ReflectionTestUtils.setField(client, "enableThinking", false);
    ReflectionTestUtils.setField(client, "temperature", 0.5d);
    ReflectionTestUtils.setField(client, "maxRetries", 1);
    ReflectionTestUtils.setField(client, "retryBackoffMs", 1);

    client.chat("sys", "usr");

    assertThat(requestEntityRef.get()).isNotNull();
    assertThat(requestEntityRef.get().getBody()).contains("\"temperature\":0.5");
  }

  @Test
  void deepSeekClient_retriesOnFailureUntilMaxAttempts() {
    RestTemplate restTemplate = mock(RestTemplate.class);
    when(restTemplate.postForEntity(anyString(), isA(HttpEntity.class), eq(String.class)))
        .thenThrow(new HttpServerErrorException(org.springframework.http.HttpStatus.BAD_GATEWAY));

    DeepSeekClient client = new DeepSeekClient(restTemplate, OBJECT_MAPPER);
    ReflectionTestUtils.setField(client, "apiKey", "test-key");
    ReflectionTestUtils.setField(client, "baseUrl", "https://api.deepseek.com/v1");
    ReflectionTestUtils.setField(client, "model", "deepseek-v4-flash");
    ReflectionTestUtils.setField(client, "enableThinking", false);
    ReflectionTestUtils.setField(client, "maxRetries", 2);
    ReflectionTestUtils.setField(client, "retryBackoffMs", 1);

    assertThatThrownBy(() -> client.chat("sys", "usr")).isInstanceOf(RuntimeException.class);
    verify(restTemplate, org.mockito.Mockito.times(2))
        .postForEntity(anyString(), isA(HttpEntity.class), eq(String.class));
  }

  @Test
  void scoreHandlesInvalidApiKeyAsFailedInsteadOfThrow() {
    DeepSeekClient client = mock(DeepSeekClient.class);
    when(client.chat(any(), any())).thenThrow(new RuntimeException("invalid api key"));

    DefaultJudgeScorer scorer = new DefaultJudgeScorer(client, new JudgePromptLibrary(), OBJECT_MAPPER);
    JudgeScore score = scorer.score(baseContext(), ScoreDimension.FAITHFULNESS);

    assertThat(score.isSuccess()).isFalse();
    assertThat(score.getFailureReason()).contains("invalid api key");
  }

  @Test
  void costEstimation_matchesSpecificationFormula() {
    DeepSeekClient.ChatResult chat = new DeepSeekClient.ChatResult("{}", 1000, 500, 10);
    assertThat(chat.estimateCostCny()).isEqualTo(new BigDecimal("0.0020"));
  }

  @Test
  @Tag("integration")
  @Timeout(value = 180)
  void realDeepSeek_5Cases_4Dimensions_stableAcrossTwoRuns() {
    String key = System.getenv("DEEPSEEK_API_KEY");
    Assumptions.assumeTrue(key != null && !key.isBlank(), "DEEPSEEK_API_KEY required");

    DeepSeekClient client = new DeepSeekClient(new RestTemplate(), OBJECT_MAPPER);
    ReflectionTestUtils.setField(client, "apiKey", key);
    ReflectionTestUtils.setField(client, "baseUrl", System.getenv().getOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1"));
    ReflectionTestUtils.setField(client, "model", System.getenv().getOrDefault("DEEPSEEK_MODEL", "deepseek-v4-flash"));
    ReflectionTestUtils.setField(client, "enableThinking", false);
    ReflectionTestUtils.setField(client, "maxTokens", 1024);
    ReflectionTestUtils.setField(client, "temperature", 0.0d);
    ReflectionTestUtils.setField(client, "maxRetries", 2);
    ReflectionTestUtils.setField(client, "retryBackoffMs", 1000);

    DefaultJudgeScorer scorer = new DefaultJudgeScorer(client, new JudgePromptLibrary(), OBJECT_MAPPER);
    for (JudgeContext context : integrationCases()) {
      for (ScoreDimension dimension : ScoreDimension.values()) {
        JudgeScore first = scorer.score(context, dimension);
        JudgeScore second = scorer.score(context, dimension);

        assertEquals(ScoreDimension.valueOf(dimension.name()), first.getDimension());
        assertTrue(first.isSuccess(), "first call should success for " + dimension);
        assertTrue(second.isSuccess(), "second call should success for " + dimension);
        assertThat(first.getScore()).isNotNull();
        assertThat(second.getScore()).isNotNull();
        BigDecimal diff = first.getScore().subtract(second.getScore()).abs();
        assertThat(diff).isLessThan(new BigDecimal("0.1"));
      }
    }
  }

  private JudgeContext baseContext() {
    JudgeContext context = new JudgeContext();
    context.setQuery("如何评价 RAG 检索效果？");
    context.setAnswer("该系统会返回检索片段并基于片段作答。");
    context.setChunks(
        List.of(
            new JudgeContext.RetrievedChunk(101L, "该系统使用向量检索召回相关文档。", BigDecimal.valueOf(0.92)),
            new JudgeContext.RetrievedChunk(102L, "RAG 可以减少幻觉并可解释。", BigDecimal.valueOf(0.81))));
    context.setCitations(
        List.of(
            citation(101L, 1001L, "RAG 可以降低幻觉"),
            citation(102L, 1002L, "RAG 支持可追溯的回答")));
    return context;
  }

  private Citation citation(Long chunkId, Long docId, String text) {
    Citation citation = new Citation();
    citation.setChunkId(chunkId);
    citation.setDocId(docId);
    citation.setTextSnippet(text);
    return citation;
  }

  private List<JudgeContext> integrationCases() {
    List<JudgeContext> cases = new ArrayList<>();

    JudgeContext c1 = baseContext();
    c1.setQuery("什么是 RAG？");
    c1.setAnswer("RAG 是检索增强生成（Retrieval-Augmented Generation），通过外部知识召回减少幻觉。");
    c1.setExpectedAnswer("RAG 使用检索到的上下文辅助生成答案");
    c1.setExpectedChunkIds(new Long[] {101L, 102L});
    cases.add(c1);

    JudgeContext c2 = baseContext();
    c2.setQuery("RAG 的主要风险是什么？");
    c2.setAnswer("风险包括检索失误、知识时效性不足以及引用错误。");
    c2.setExpectedAnswer("检索误召回和知识过时会影响答案正确性");
    c2.setExpectedChunkIds(new Long[] {102L});
    cases.add(c2);

    JudgeContext c3 = baseContext();
    c3.setQuery("怎么降低幻觉？");
    c3.setAnswer("可以通过引用约束与评分机制减少幻觉。");
    c3.setExpectedAnswer("通过证据约束与检索校验可降低幻觉");
    c3.setExpectedChunkIds(new Long[] {101L, 102L});
    cases.add(c3);

    JudgeContext c4 = baseContext();
    c4.setQuery("RAG 是否会取代传统检索？");
    c4.setAnswer("RAG 是增强生成质量和事实性的技术，不是纯检索替代。");
    c4.setExpectedAnswer("RAG 与检索协作提升生成");
    c4.setExpectedChunkIds(new Long[] {101L});
    cases.add(c4);

    JudgeContext c5 = baseContext();
    c5.setQuery("召回不足如何处理？");
    c5.setAnswer("可以提高 top-k 或优化索引策略。");
    c5.setExpectedAnswer("通过增大召回数量和优化索引可提升覆盖");
    c5.setExpectedChunkIds(new Long[] {101L, 102L});
    cases.add(c5);

    return cases;
  }
}
