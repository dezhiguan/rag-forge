package com.ragforge.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.answer.AnswerModels.Citation;
import com.ragforge.judge.sampler.AnswerJudgeMessage;
import com.ragforge.mapper.AnswerLogMapper;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.JudgeResultMapper;
import com.ragforge.metrics.RagforgeMetrics;
import com.ragforge.model.entity.AnswerLog;
import com.ragforge.model.entity.JudgeResult;
import com.ragforge.support.BaseIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Assumptions;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.boot.test.context.TestConfiguration;

@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class JudgeOrchestratorUnitTest {

  @Test
  void judgeAllDimensionsSuccessInsertsCompletedResult() {
    AnswerLogMapper answerLogMapper = org.mockito.Mockito.mock(AnswerLogMapper.class);
    DocumentChunkMapper documentChunkMapper = org.mockito.Mockito.mock(DocumentChunkMapper.class);
    JudgeResultMapper judgeResultMapper = org.mockito.Mockito.mock(JudgeResultMapper.class);
    JudgeScorer scorer = org.mockito.Mockito.mock(JudgeScorer.class);
    RagforgeMetrics metrics = org.mockito.Mockito.mock(RagforgeMetrics.class);
    ObjectMapper objectMapper = new ObjectMapper();

    AnswerLog answerLog = new AnswerLog();
    answerLog.setId(101L);
    answerLog.setTenantId("default");
    answerLog.setQuery("how to test?");
    answerLog.setAnswer("answer");
    answerLog.setKbIdsCsv("11,22");
    answerLog.setCitationsSnapshot(toCitationSnapshot(List.of(citation(11L), citation(22L))));
    when(answerLogMapper.selectByIdWithKbIdsCsv(101L)).thenReturn(answerLog);

    when(scorer.score(any(), eq(ScoreDimension.FAITHFULNESS))).thenReturn(success(ScoreDimension.FAITHFULNESS, "0.9"));
    when(scorer.score(any(), eq(ScoreDimension.CONTEXT_PRECISION))).thenReturn(success(ScoreDimension.CONTEXT_PRECISION, "0.8"));
    when(scorer.score(any(), eq(ScoreDimension.ANSWER_RELEVANCE))).thenReturn(success(ScoreDimension.ANSWER_RELEVANCE, "0.85"));
    // COMPOSITE 不再经 LLM scorer,改由三项子维度确定性计算(见 JudgeOrchestrator.computeComposite)。
    org.mockito.Mockito.doAnswer(
            invocation -> {
              JudgeResult inserted = invocation.getArgument(0);
              assertThat(inserted.getStatus()).isEqualTo("RUNNING");
              inserted.setId(1001L);
              return 1;
            })
        .when(judgeResultMapper)
        .insert(any(JudgeResult.class));

    JudgeOrchestrator orchestrator =
        new JudgeOrchestrator(answerLogMapper, documentChunkMapper, judgeResultMapper, scorer, metrics, objectMapper,
            org.mockito.Mockito.mock(com.ragforge.modelcenter.ModelUsageRecorder.class));
    AnswerJudgeMessage msg = new AnswerJudgeMessage();
    msg.setAnswerLogId(101L);
    msg.setSource("PRODUCTION");

    orchestrator.judge(msg);

    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(judgeResultMapper, scorer);
    inOrder.verify(judgeResultMapper).insert(any(JudgeResult.class));
    inOrder.verify(scorer).score(any(), eq(ScoreDimension.FAITHFULNESS));
    // 综合分=三项子维度均值确定性计算:(0.9+0.8+0.85)/3 = 0.85;且不再调用 LLM 综合判分。
    verify(judgeResultMapper)
        .updateById(
            argThat(
                (JudgeResult r) ->
                    "COMPLETED".equals(r.getStatus())
                        && r.getId().equals(1001L)
                        && r.getOverallScore() != null
                        && r.getOverallScore().compareTo(new java.math.BigDecimal("0.85")) == 0));
    verify(scorer, org.mockito.Mockito.never()).score(any(), eq(ScoreDimension.COMPOSITE));
    verify(metrics).recordJudgeCost(eq("PRODUCTION"), any(BigDecimal.class));
  }

  @Test
  void judgeFailedDimensionSetsFailedStatus() {
    AnswerLogMapper answerLogMapper = org.mockito.Mockito.mock(AnswerLogMapper.class);
    DocumentChunkMapper documentChunkMapper = org.mockito.Mockito.mock(DocumentChunkMapper.class);
    JudgeResultMapper judgeResultMapper = org.mockito.Mockito.mock(JudgeResultMapper.class);
    JudgeScorer scorer = org.mockito.Mockito.mock(JudgeScorer.class);
    RagforgeMetrics metrics = org.mockito.Mockito.mock(RagforgeMetrics.class);
    ObjectMapper objectMapper = new ObjectMapper();

    AnswerLog answerLog = new AnswerLog();
    answerLog.setId(102L);
    answerLog.setTenantId("default");
    answerLog.setQuery("how to fail?");
    answerLog.setAnswer("answer");
    answerLog.setKbIdsCsv("11");
    answerLog.setCitationsSnapshot("[]");
    when(answerLogMapper.selectByIdWithKbIdsCsv(102L)).thenReturn(answerLog);

    when(scorer.score(any(), eq(ScoreDimension.FAITHFULNESS))).thenReturn(success(ScoreDimension.FAITHFULNESS, "0.9"));
    when(scorer.score(any(), eq(ScoreDimension.CONTEXT_PRECISION))).thenReturn(fail(ScoreDimension.CONTEXT_PRECISION, "llm error"));
    when(scorer.score(any(), eq(ScoreDimension.ANSWER_RELEVANCE))).thenReturn(success(ScoreDimension.ANSWER_RELEVANCE, "0.85"));
    // COMPOSITE 不再经 LLM scorer,改由三项子维度确定性计算(见 JudgeOrchestrator.computeComposite)。
    org.mockito.Mockito.doAnswer(
            invocation -> {
              JudgeResult inserted = invocation.getArgument(0);
              assertThat(inserted.getStatus()).isEqualTo("RUNNING");
              inserted.setId(1002L);
              return 1;
            })
        .when(judgeResultMapper)
        .insert(any(JudgeResult.class));

    JudgeOrchestrator orchestrator =
        new JudgeOrchestrator(answerLogMapper, documentChunkMapper, judgeResultMapper, scorer, metrics, objectMapper,
            org.mockito.Mockito.mock(com.ragforge.modelcenter.ModelUsageRecorder.class));
    AnswerJudgeMessage msg = new AnswerJudgeMessage();
    msg.setAnswerLogId(102L);
    msg.setSource("PRODUCTION");

    orchestrator.judge(msg);

    verify(judgeResultMapper)
        .updateById(argThat((JudgeResult r) -> "FAILED".equals(r.getStatus()) && "llm error".equals(r.getFailureReason())));
  }

  @Test
  void judgeScorerExceptionUpdatesFailedStatus() {
    AnswerLogMapper answerLogMapper = org.mockito.Mockito.mock(AnswerLogMapper.class);
    DocumentChunkMapper documentChunkMapper = org.mockito.Mockito.mock(DocumentChunkMapper.class);
    JudgeResultMapper judgeResultMapper = org.mockito.Mockito.mock(JudgeResultMapper.class);
    JudgeScorer scorer = org.mockito.Mockito.mock(JudgeScorer.class);
    RagforgeMetrics metrics = org.mockito.Mockito.mock(RagforgeMetrics.class);
    ObjectMapper objectMapper = new ObjectMapper();

    AnswerLog answerLog = new AnswerLog();
    answerLog.setId(104L);
    answerLog.setTenantId("default");
    answerLog.setQuery("how to timeout?");
    answerLog.setAnswer("answer");
    answerLog.setKbIdsCsv("11");
    answerLog.setCitationsSnapshot("[]");
    when(answerLogMapper.selectByIdWithKbIdsCsv(104L)).thenReturn(answerLog);
    when(scorer.score(any(), eq(ScoreDimension.FAITHFULNESS)))
        .thenThrow(new RuntimeException("scorer timeout"));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              JudgeResult inserted = invocation.getArgument(0);
              assertThat(inserted.getStatus()).isEqualTo("RUNNING");
              inserted.setId(1004L);
              return 1;
            })
        .when(judgeResultMapper)
        .insert(any(JudgeResult.class));

    JudgeOrchestrator orchestrator =
        new JudgeOrchestrator(answerLogMapper, documentChunkMapper, judgeResultMapper, scorer, metrics, objectMapper,
            org.mockito.Mockito.mock(com.ragforge.modelcenter.ModelUsageRecorder.class));
    AnswerJudgeMessage msg = new AnswerJudgeMessage();
    msg.setAnswerLogId(104L);
    msg.setSource("PRODUCTION");

    orchestrator.judge(msg);

    verify(judgeResultMapper)
        .updateById(
            argThat(
                (JudgeResult r) ->
                    "FAILED".equals(r.getStatus())
                        && r.getId().equals(1004L)
                        && "scorer timeout".equals(r.getFailureReason())));
  }

  @Test
  void testFailureReasonTruncated() {
    AnswerLogMapper answerLogMapper = org.mockito.Mockito.mock(AnswerLogMapper.class);
    DocumentChunkMapper documentChunkMapper = org.mockito.Mockito.mock(DocumentChunkMapper.class);
    JudgeResultMapper judgeResultMapper = org.mockito.Mockito.mock(JudgeResultMapper.class);
    JudgeScorer scorer = org.mockito.Mockito.mock(JudgeScorer.class);
    RagforgeMetrics metrics = org.mockito.Mockito.mock(RagforgeMetrics.class);
    ObjectMapper objectMapper = new ObjectMapper();

    String longReason = "x".repeat(500);

    AnswerLog answerLog = new AnswerLog();
    answerLog.setId(105L);
    answerLog.setTenantId("default");
    answerLog.setQuery("long failure?");
    answerLog.setAnswer("answer");
    answerLog.setKbIdsCsv("11");
    answerLog.setCitationsSnapshot("[]");
    when(answerLogMapper.selectByIdWithKbIdsCsv(105L)).thenReturn(answerLog);

    when(scorer.score(any(), eq(ScoreDimension.FAITHFULNESS))).thenReturn(fail(ScoreDimension.FAITHFULNESS, longReason));
    when(scorer.score(any(), eq(ScoreDimension.CONTEXT_PRECISION))).thenReturn(success(ScoreDimension.CONTEXT_PRECISION, "0.8"));
    when(scorer.score(any(), eq(ScoreDimension.ANSWER_RELEVANCE))).thenReturn(success(ScoreDimension.ANSWER_RELEVANCE, "0.85"));
    // COMPOSITE 不再经 LLM scorer,改由三项子维度确定性计算(见 JudgeOrchestrator.computeComposite)。
    org.mockito.Mockito.doAnswer(
            invocation -> {
              JudgeResult inserted = invocation.getArgument(0);
              inserted.setId(1005L);
              return 1;
            })
        .when(judgeResultMapper)
        .insert(any(JudgeResult.class));

    JudgeOrchestrator orchestrator =
        new JudgeOrchestrator(answerLogMapper, documentChunkMapper, judgeResultMapper, scorer, metrics, objectMapper,
            org.mockito.Mockito.mock(com.ragforge.modelcenter.ModelUsageRecorder.class));
    AnswerJudgeMessage msg = new AnswerJudgeMessage();
    msg.setAnswerLogId(105L);
    msg.setSource("PRODUCTION");

    orchestrator.judge(msg);

    verify(judgeResultMapper)
        .updateById(
            argThat(
                (JudgeResult r) -> {
                  String reason = r.getFailureReason();
                  return "FAILED".equals(r.getStatus())
                      && reason != null
                      && reason.length() == 240
                      && reason.endsWith("...")
                      && reason.length() <= 256;
                }));
  }

  @Test
  void answerLogNotFoundSkipsSilently() {
    AnswerLogMapper answerLogMapper = org.mockito.Mockito.mock(AnswerLogMapper.class);
    DocumentChunkMapper documentChunkMapper = org.mockito.Mockito.mock(DocumentChunkMapper.class);
    JudgeResultMapper judgeResultMapper = org.mockito.Mockito.mock(JudgeResultMapper.class);
    JudgeScorer scorer = org.mockito.Mockito.mock(JudgeScorer.class);
    RagforgeMetrics metrics = org.mockito.Mockito.mock(RagforgeMetrics.class);
    ObjectMapper objectMapper = new ObjectMapper();

    when(answerLogMapper.selectByIdWithKbIdsCsv(103L)).thenReturn(null);

    JudgeOrchestrator orchestrator =
        new JudgeOrchestrator(answerLogMapper, documentChunkMapper, judgeResultMapper, scorer, metrics, objectMapper,
            org.mockito.Mockito.mock(com.ragforge.modelcenter.ModelUsageRecorder.class));
    AnswerJudgeMessage msg = new AnswerJudgeMessage();
    msg.setAnswerLogId(103L);

    orchestrator.judge(msg);

    verify(judgeResultMapper, never()).insert(any(JudgeResult.class));
  }

  private JudgeScore success(ScoreDimension dim, String score) {
    return JudgeScore.success(
        dim,
        new BigDecimal(score),
        "ok",
        List.of(),
        "{\"score\":" + score + "}",
        10,
        BigDecimal.valueOf(0.001),
        10,
        5,
        true);
  }

  private JudgeScore fail(ScoreDimension dim, String reason) {
    return JudgeScore.failed(dim, reason);
  }

  private Citation citation(Long chunkId) {
    Citation c = new Citation();
    c.setChunkId(chunkId);
    c.setDocId(1L);
    c.setTextSnippet("snippet");
    return c;
  }

  private String toCitationSnapshot(List<Citation> citations) {
    try {
      return new ObjectMapper().writeValueAsString(citations);
    } catch (Exception e) {
      return "[]";
    }
  }
}

@ExtendWith(SpringExtension.class)
@SpringBootTest(
    classes = JudgeOrchestratorIntegrationTest.IntegrationConfig.class,
    properties = {
      "spring.profiles.active=test",
      "mybatis-plus.configuration.map-underscore-to-camel-case=true",
      "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration"
    })
@Transactional
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RAGFORGE_RUN_REAL_JUDGE_IT", matches = "true")
class JudgeOrchestratorIntegrationTest extends BaseIntegrationTest {

  @Autowired
  private AnswerLogMapper answerLogMapper;

  @Autowired
  private JudgeResultMapper judgeResultMapper;

  @Autowired
  private JudgeOrchestrator orchestrator;

  @AfterEach
  void cleanup(@Autowired JdbcTemplate jdbcTemplate) {
    try {
      jdbcTemplate.update("DELETE FROM judge_results WHERE source='PRODUCTION' AND source IS NOT NULL");
    } catch (Exception ignored) {
    }
  }

  @Test
  void realDeepSeekMessageWritesJudgeResult() {
    String key = System.getenv("DEEPSEEK_API_KEY");
    Assumptions.assumeTrue(key != null && !key.isBlank(), "DEEPSEEK_API_KEY required");

    AnswerLog answerLog = new AnswerLog();
    answerLog.setTenantId("default");
    answerLog.setKbIdsCsv("100");
    answerLog.setQuery("RAG 是什么？");
    answerLog.setAnswer("RAG 是检索增强生成。它通过检索上下文减少幻觉。");
    answerLog.setCitationsSnapshot("[]");
    answerLog.setRetrievalStrategy("hybrid");
    answerLog.setAnswerMode("ON");
    answerLog.setLlmModel("deepseek-chat");
    answerLog.setPromptTokens(10);
    answerLog.setCompletionTokens(20);
    answerLog.setRetrievalLatencyMs(30);
    answerLog.setLlmLatencyMs(40);
    answerLog.setTotalLatencyMs(70);
    answerLog.setTraceId("integration-trace");
    answerLog.setGuardRailResult("PASS");
    answerLog.setCreatedAt(LocalDateTime.now());
    answerLogMapper.insertAnswerLog(answerLog);

    AnswerJudgeMessage msg = new AnswerJudgeMessage();
    msg.setAnswerLogId(answerLog.getId());
    msg.setSource("PRODUCTION");

    orchestrator.judge(msg);

    List<JudgeResult> rows =
        judgeResultMapper.selectList(new QueryWrapper<JudgeResult>().eq("answer_log_id", answerLog.getId()));

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getAnswerLogId()).isEqualTo(answerLog.getId());
  }

  @TestConfiguration
  @EnableAutoConfiguration
  @MapperScan("com.ragforge.mapper")
  static class IntegrationConfig {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    com.ragforge.metrics.RagforgeMetrics ragforgeMetrics() {
      return new com.ragforge.metrics.RagforgeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Bean
    org.springframework.web.client.RestTemplate restTemplate() {
      return new org.springframework.web.client.RestTemplate();
    }

    @Bean
    com.ragforge.service.LlmService llmService() {
      return org.mockito.Mockito.mock(com.ragforge.service.LlmService.class);
    }

    @Bean
    RocketMQTemplate rocketMQTemplate() {
      return org.mockito.Mockito.mock(RocketMQTemplate.class);
    }
  }
}
