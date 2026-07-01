package com.ragforge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.mapper.AnswerLogMapper;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.JudgeResultMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.entity.AnswerLog;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.model.entity.JudgeResult;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.model.vo.CaseDetailVo;
import com.ragforge.model.vo.CostSummaryVo;
import com.ragforge.model.vo.KbSliceVo;
import com.ragforge.model.vo.OverviewVo;
import com.ragforge.storage.ChunkImageResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class JudgeQueryServiceImplTest {

  @Mock private JdbcTemplate jdbcTemplate;
  @Mock private JudgeResultMapper judgeResultMapper;
  @Mock private AnswerLogMapper answerLogMapper;
  @Mock private DocumentChunkMapper documentChunkMapper;
  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private ChunkImageResolver chunkImageResolver;

  private JudgeQueryServiceImpl service;

  @BeforeEach
  void setUp() {
    service =
        new JudgeQueryServiceImpl(
            jdbcTemplate,
            judgeResultMapper,
            answerLogMapper,
            documentChunkMapper,
            knowledgeBaseMapper,
            new ObjectMapper(),
            chunkImageResolver);
  }

  @Test
  void overview_aggregatesCurrentAndPreviousWindows() {
    LocalDate today = LocalDate.now();
    when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.<Object[]>any()))
        .thenReturn(
            List.of(
                metricsRow(today.minusDays(8), 10, 1, "0.50", "0.60", "0.70", "0.80", "1.00"),
                metricsRow(today.minusDays(1), 20, 2, "0.80", "0.90", "0.70", "0.60", "2.00")));

    OverviewVo overview = service.overview(7, null, null);

    assertThat(overview.getTrend()).hasSize(2);
    assertThat(overview.getSamples().getTotalSamples()).isEqualTo(20);
    assertThat(overview.getSamples().getFailedSamples()).isEqualTo(2);
    assertThat(overview.getSamples().getFailedRate()).isEqualByComparingTo("0.1000");
    assertThat(overview.getKpis().getOverallScore()).isEqualByComparingTo("0.6000");
    assertThat(overview.getKpis().getFaithfulness()).isEqualByComparingTo("0.8000");
    assertThat(overview.getKpis().getCostLastPeriodCny()).isEqualByComparingTo("2.0000");
    assertThat(overview.getKpis().getOverallChange()).isEqualByComparingTo("-0.2500");
  }

  @Test
  void overviewSupportsKbIdAndScopedKbIds() {
    LocalDate today = LocalDate.now();
    when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.anyLong(), any(LocalDate.class)))
        .thenReturn(List.of(metricsRow(today, 3, 0, "0.70", "0.70", "0.70", "0.70", "1.00")));
    OverviewVo kbOverview = service.overview(7, 16L, null);
    assertThat(kbOverview.getSamples().getTotalSamples()).isEqualTo(3);
    assertThat(kbOverview.getKpis().getOverallScore()).isEqualByComparingTo("0.7000");

    when(jdbcTemplate.queryForList(
            anyString(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            any(LocalDate.class)))
        .thenReturn(List.of(metricsRow(today, 5, 1, "0.90", "0.80", "0.70", "0.60", "2.00")));
    OverviewVo scoped = service.overview(7, null, Set.of(16L, 17L));
    assertThat(scoped.getSamples().getTotalSamples()).isEqualTo(5);
    assertThat(scoped.getKpis().getCostLastPeriodCny()).isEqualByComparingTo("2.0000");
  }

  @Test
  void byKb_groupsRowsAndLoadsKbNames() {
    LocalDate today = LocalDate.now();
    when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.<Object[]>any()))
        .thenReturn(
            List.of(
                kbMetricsRow(16L, today.minusDays(1), 4, "0.70"),
                kbMetricsRow(17L, today.minusDays(1), 8, "0.90")));
    KnowledgeBase kb16 = kb(16L, "kb-16");
    KnowledgeBase kb17 = kb(17L, "kb-17");
    when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(kb16, kb17));

    List<KbSliceVo> result = service.byKb(7, null);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).getKbId()).isEqualTo(17L);
    assertThat(result.get(0).getKbName()).isEqualTo("kb-17");
    assertThat(result.get(0).getSampleCount()).isEqualTo(8);
    assertThat(result.get(0).getOverallScore()).isEqualByComparingTo("0.9000");
  }

  @Test
  void byKb_scopedRowsLoadsNamesAndSkipsOrphanKb() {
    LocalDate today = LocalDate.now();
    when(jdbcTemplate.queryForList(
            anyString(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            any(LocalDate.class)))
        .thenReturn(
            List.of(
                kbMetricsRow(16L, today.minusDays(1), 2, "0.40"),
                kbMetricsRow(17L, today.minusDays(1), 1, "0.80")));
    // kb-17 名字查不到（库已删除的孤儿指标）：应被跳过，避免排行出现裸 id。
    when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(kb(16L, "kb-16")));

    List<KbSliceVo> result = service.byKb(7, Set.of(16L, 17L));

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().getKbId()).isEqualTo(16L);
    assertThat(result.getFirst().getKbName()).isEqualTo("kb-16");
    assertThat(result.getFirst().getSampleCount()).isEqualTo(2);
  }

  @Test
  void overviewAndByKbReturnEmptyForEmptyScope() {
    OverviewVo overview = service.overview(7, null, Set.of());
    List<KbSliceVo> slices = service.byKb(7, Set.of());

    assertThat(overview.getSamples().getTotalSamples()).isZero();
    assertThat(overview.getTrend()).isEmpty();
    assertThat(overview.getKpis().getOverallScore()).isEqualByComparingTo("0.0000");
    assertThat(slices).isEmpty();
  }

  @Test
  void worstCasesLoadsGlobalAndKbRowsAndExtractsTopIssue() {
    LocalDateTime createdAt = LocalDateTime.now();
    List<Map<String, Object>> rows =
        List.of(
            Map.of(
                "id",
                    "7",
                "answer_log_id",
                70L,
                "query",
                "bad answer",
                "overall_score",
                    0.20d,
                "created_at",
                    java.sql.Timestamp.valueOf(createdAt),
                "judge_raw_response",
                "{\"FAITHFULNESS\":{\"issues\":[\"missing citation\"]}}"));
    when(jdbcTemplate.queryForList(
            anyString(), any(LocalDate.class), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(rows);
    when(jdbcTemplate.queryForList(
            anyString(),
            any(LocalDate.class),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(rows);

    var global = service.worstCases(0, 0, null, null);
    var byKb = service.worstCases(3, 7, 16L, null);

    assertThat(global).hasSize(1);
    assertThat(global.getFirst().getJudgeResultId()).isEqualTo(7L);
    assertThat(global.getFirst().getTopIssue()).isEqualTo("missing citation");
    assertThat(global.getFirst().getOverallScore()).isEqualByComparingTo("0.2000");
    assertThat(byKb.getFirst().getAnswerLogId()).isEqualTo(70L);
  }

  @Test
  void worstCasesReturnsEmptyForEmptyScopedKbIds() {
    assertThat(service.worstCases(10, 7, null, Set.of())).isEmpty();
  }

  @Test
  void cost_summarizesPlatformRowsAndSources() {
    when(jdbcTemplate.queryForMap(anyString(), any()))
        .thenReturn(
            Map.of(
                "total_cost_cny",
                new BigDecimal("14.00"),
                "total_samples",
                7,
                "failed_samples",
                2));
    when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.<Object[]>any()))
        .thenReturn(
            List.of(
                Map.of("source", "production", "total_cost", new BigDecimal("10.00")),
                Map.of("source", "manual", "total_cost", new BigDecimal("4.00"))));

    CostSummaryVo cost = service.cost(7, null);

    assertThat(cost.getTotalCny()).isEqualByComparingTo("14.0000");
    assertThat(cost.getDailyAverageCny()).isEqualByComparingTo("2.0000");
    assertThat(cost.getMonthlyProjectedCny()).isEqualByComparingTo("60.0000");
    assertThat(cost.getTotalCalls()).isEqualTo(7);
    assertThat(cost.getFailedCalls()).isEqualTo(2);
    assertThat(cost.getCostBySource().get("PRODUCTION")).isEqualByComparingTo("10.0000");
    assertThat(cost.getCostBySource().get("MANUAL")).isEqualByComparingTo("4.0000");
  }

  @Test
  void cost_emptyScopeReturnsZeroCost() {
    CostSummaryVo cost = service.cost(7, java.util.Set.of());

    assertThat(cost.getTotalCny()).isEqualByComparingTo("0.0000");
    assertThat(cost.getTotalCalls()).isZero();
    assertThat(cost.getCostBySource()).containsKeys("PRODUCTION", "GOLDEN_SET", "MANUAL");
  }

  @Test
  void cost_summarizesScopedKbRowsAndSources() {
    when(jdbcTemplate.queryForMap(
            anyString(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            any(LocalDate.class)))
        .thenReturn(
            Map.of(
                "total_cost_cny",
                "9.00",
                "total_samples",
                "6",
                "failed_samples",
                "1"));
    when(jdbcTemplate.queryForList(
            anyString(),
            any(LocalDate.class),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(
            List.of(
                Map.of("source", " ", "total_cost", "100.00"),
                Map.of("source", "golden_set", "total_cost", "2.50"),
                Map.of("source", "manual", "total_cost", new BigDecimal("6.50"))));

    CostSummaryVo cost = service.cost(3, Set.of(16L, 17L));

    assertThat(cost.getTotalCny()).isEqualByComparingTo("9.0000");
    assertThat(cost.getDailyAverageCny()).isEqualByComparingTo("3.0000");
    assertThat(cost.getMonthlyProjectedCny()).isEqualByComparingTo("90.0000");
    assertThat(cost.getTotalCalls()).isEqualTo(6);
    assertThat(cost.getFailedCalls()).isEqualTo(1);
    assertThat(cost.getCostBySource().get("GOLDEN_SET")).isEqualByComparingTo("2.5000");
    assertThat(cost.getCostBySource().get("MANUAL")).isEqualByComparingTo("6.5000");
  }

  @Test
  void caseDetailHandlesMalformedSnapshotsAndRawIssuesFallbacks() {
    JudgeResult result = judgeResult(100L, 56L);
    result.setQuery("   ");
    result.setJudgeReasoning(null);
    result.setJudgeRawResponse(
        """
        {"COMPOSITE":{"issues":["bottleneck=retrieval","add citations"]},
         "FAITHFULNESS":{"reasoning":"faith reasoning"}}
        """);
    AnswerLog log = new AnswerLog();
    log.setId(56L);
    log.setQuery("logged query");
    log.setCitationsSnapshot("not-json");
    when(judgeResultMapper.selectById(100L)).thenReturn(result);
    when(answerLogMapper.selectByIdWithKbIdsCsv(56L)).thenReturn(log);

    CaseDetailVo detail = service.caseDetail(100L);

    assertThat(detail.getQuery()).isEqualTo("logged query");
    assertThat(detail.getChunks()).isEmpty();
    assertThat(detail.getJudgeReasoning()).isEqualTo("faith reasoning");
    assertThat(detail.getBottleneck()).isEqualTo("retrieval");
    assertThat(detail.getImprovements()).isEmpty();
  }

  @Test
  void caseDetail_resolvesScoresReasoningAndChunks() {
    JudgeResult result = judgeResult(99L, 55L);
    AnswerLog log = new AnswerLog();
    log.setId(55L);
    log.setQuery("logged query");
    log.setAnswer("answer text");
    log.setCreatedAt(LocalDateTime.now());
    log.setCitationsSnapshot(
        """
        [{"id":1,"chunkId":700,"docId":800,"textSnippet":"hit snippet","score":0.91,"relevant":true}]
        """);
    DocumentChunk chunk = new DocumentChunk();
    chunk.setId(700L);
    chunk.setContent("full chunk content");
    when(judgeResultMapper.selectById(99L)).thenReturn(result);
    when(answerLogMapper.selectByIdWithKbIdsCsv(55L)).thenReturn(log);
    when(documentChunkMapper.selectBatchIds(List.of(700L))).thenReturn(List.of(chunk));
    when(chunkImageResolver.presignedUrls(List.of(700L))).thenReturn(Map.of(700L, "https://img"));

    CaseDetailVo detail = service.caseDetail(99L);

    assertThat(detail.getJudgeResultId()).isEqualTo(99L);
    assertThat(detail.getQuery()).isEqualTo("judge query");
    assertThat(detail.getAnswer()).isEqualTo("answer text");
    assertThat(detail.getKbIds()).containsExactly(16L, 17L);
    assertThat(detail.getScores().get("overall")).isEqualByComparingTo("0.7000");
    assertThat(detail.getJudgeReasoning()).isEqualTo("explicit reasoning");
    assertThat(detail.getImprovements()).isEmpty();
    assertThat(detail.getBottleneck()).isEqualTo("context");
    assertThat(detail.getChunks()).hasSize(1);
    assertThat(detail.getChunks().getFirst().getContent()).isEqualTo("full chunk content");
    assertThat(detail.getChunks().getFirst().getImageUrl()).isEqualTo("https://img");
  }

  @Test
  void caseDetail_validatesMissingResult() {
    assertThatThrownBy(() -> service.caseDetail(null))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(400));

    when(judgeResultMapper.selectById(404L)).thenReturn(null);
    assertThatThrownBy(() -> service.caseDetail(404L))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(404));
  }

  @Test
  void simpleCountersDefaultNullToZero() {
    when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any())).thenReturn(new BigDecimal("3.50"));
    assertThat(service.costThisMonth()).isEqualByComparingTo("3.5000");

    when(jdbcTemplate.queryForObject(anyString(), any(Class.class))).thenReturn(null);
    assertThat(service.goldenSetEnabledQuestionCount()).isZero();
  }

  private static Map<String, Object> metricsRow(
      LocalDate date,
      int samples,
      int failed,
      String faithfulness,
      String contextPrecision,
      String answerRelevance,
      String overall,
      String cost) {
    return Map.of(
        "date",
        date,
        "sample_count",
        samples,
        "failed_count",
        failed,
        "faithfulness_p50",
        new BigDecimal(faithfulness),
        "context_precision_p50",
        new BigDecimal(contextPrecision),
        "answer_relevance_p50",
        new BigDecimal(answerRelevance),
        "overall_p50",
        new BigDecimal(overall),
        "overall_p95",
        new BigDecimal(overall),
        "overall_mean",
        new BigDecimal(overall),
        "total_cost_cny",
        new BigDecimal(cost));
  }

  private static Map<String, Object> kbMetricsRow(Long kbId, LocalDate date, int samples, String overall) {
    Map<String, Object> row = new java.util.LinkedHashMap<>(metricsRow(date, samples, 0, overall, overall, overall, overall, "0"));
    row.put("kb_id", kbId);
    return row;
  }

  private static KnowledgeBase kb(Long id, String name) {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(id);
    kb.setName(name);
    return kb;
  }

  private static JudgeResult judgeResult(Long id, Long answerLogId) {
    JudgeResult result = new JudgeResult();
    result.setId(id);
    result.setAnswerLogId(answerLogId);
    result.setKbIds(new Long[] {16L, 17L});
    result.setQuery("judge query");
    result.setFaithfulness(new BigDecimal("0.80"));
    result.setContextPrecision(new BigDecimal("0.75"));
    result.setAnswerRelevance(new BigDecimal("0.65"));
    result.setOverallScore(new BigDecimal("0.70"));
    result.setJudgeReasoning("explicit reasoning");
    result.setJudgeRawResponse(
        """
        {"COMPOSITE":{"improvements":["improve grounding"],"bottleneck":"context","reasoning":"raw reasoning"}}
        """);
    return result;
  }
}
