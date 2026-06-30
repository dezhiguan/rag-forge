package com.ragforge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.EvalDatasetMapper;
import com.ragforge.mapper.EvalExperimentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.mapper.ModelUsageDailyMapper;
import com.ragforge.mapper.RetrievalLogMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.EvalDataset;
import com.ragforge.model.entity.EvalExperiment;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.model.entity.RetrievalLog;
import com.ragforge.model.vo.DashboardActivityVO;
import com.ragforge.model.vo.DashboardMetricsVO;
import com.ragforge.security.AdminOverrideHolder;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MetricsServiceImplTest {

  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private DocumentMapper documentMapper;
  @Mock private DocumentChunkMapper documentChunkMapper;
  @Mock private RetrievalLogMapper retrievalLogMapper;
  @Mock private ModelUsageDailyMapper modelUsageDailyMapper;
  @Mock private EvalExperimentMapper evalExperimentMapper;
  @Mock private EvalDatasetMapper evalDatasetMapper;

  @InjectMocks private MetricsServiceImpl metricsService;

  @BeforeEach
  void stubCounts() {
    RagAuthContextHolder.set(
        new RagAuthContext(1L, "ADMIN", Set.of(), Set.of(), Set.of(), "USER", "1"));
    AdminOverrideHolder.activate("metrics-test");
    when(knowledgeBaseMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);
    when(documentMapper.selectCount(any())).thenReturn(10L);
    when(documentChunkMapper.selectCount(any())).thenReturn(100L);
    when(retrievalLogMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L);
    when(retrievalLogMapper.selectMaps(any()))
        .thenReturn(
            List.of(Map.of("avg_latency_ms", 42.6)),
            List.of(
                Map.of(
                    "total",
                    10,
                    "success_cnt",
                    8,
                    "zero_cnt",
                    2,
                    "avg_recall",
                    4.5,
                    "p50",
                    31.2,
                    "p95",
                    99.7,
                    "rewrite_cnt",
                    4,
                    "avg_rerank",
                    0.81)),
            List.of(Map.of("d", "06-30", "cnt", 7, "p95", 120.2)));
    when(documentMapper.selectMaps(any()))
        .thenReturn(
            List.of(
                Map.of("st", "completed", "cnt", 6),
                Map.of("st", "failed", "cnt", 2),
                Map.of("st", "queued", "cnt", 3),
                Map.of("st", "reprocessing", "cnt", 1)));
    when(modelUsageDailyMapper.aggregateCostSince(any(), any()))
        .thenReturn(
            List.of(
                Map.of("purpose", "embedding", "tokens", 100, "cost", new BigDecimal("1.25")),
                Map.of("purpose", "rerank", "tokens", 50, "cost", new BigDecimal("0.75")),
                Map.of("purpose", "answer", "tokens", 200, "cost", new BigDecimal("3.00"))));
  }

  @AfterEach
  void clearContext() {
    RagAuthContextHolder.clear();
    AdminOverrideHolder.clear();
    com.ragforge.security.OrgContextHolder.clear();
  }

  @Test
  void dashboard_loadsMetricsAndRecentActivities() {
    Document completed = document(1L, 10L, "done.pdf", "completed");
    Document failed = document(2L, 10L, "bad.pdf", "failed");
    when(documentMapper.selectList(any(LambdaQueryWrapper.class)))
        .thenReturn(List.of(completed, failed));
    when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class)))
        .thenReturn(List.of(kb(10L, "kb-10")));

    EvalExperiment exp = experiment(1L, 20L, "hybrid", "completed", new BigDecimal("0.7500"));
    when(evalExperimentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(exp);
    when(evalExperimentMapper.selectList(any(LambdaQueryWrapper.class)))
        .thenReturn(List.of(exp));
    when(evalDatasetMapper.selectList(any(LambdaQueryWrapper.class)))
        .thenReturn(List.of(dataset(20L, "eval-ds")));

    RetrievalLog log = new RetrievalLog();
    log.setStrategy("vector");
    log.setLatencyMs(88);
    log.setCreatedAt(LocalDateTime.now());
    when(retrievalLogMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(log));

    DashboardMetricsVO vo = metricsService.dashboard();

    assertThat(vo.getKbCount()).isEqualTo(3L);
    assertThat(vo.getDocumentCount()).isEqualTo(10L);
    assertThat(vo.getChunkCount()).isEqualTo(100L);
    assertThat(vo.getTodayApiCalls()).isEqualTo(5L);
    assertThat(vo.getAvgLatencyMs()).isEqualTo(43L);
    assertThat(vo.getHitRate()).isEqualTo(0.75);
    assertThat(vo.getPeriodApiCalls()).isEqualTo(10L);
    assertThat(vo.getSearchSuccessRate()).isEqualTo(0.8);
    assertThat(vo.getZeroResultRate()).isEqualTo(0.25);
    assertThat(vo.getAvgRecallCount()).isEqualTo(4.5);
    assertThat(vo.getP50LatencyMs()).isEqualTo(31L);
    assertThat(vo.getP95LatencyMs()).isEqualTo(100L);
    assertThat(vo.getRewriteRate()).isEqualTo(0.5);
    assertThat(vo.getAvgRerankScore()).isEqualTo(0.81);
    assertThat(vo.getIngestCompleted()).isEqualTo(6L);
    assertThat(vo.getIngestFailed()).isEqualTo(2L);
    assertThat(vo.getIngestQueued()).isEqualTo(3L);
    assertThat(vo.getIngestProcessing()).isEqualTo(1L);
    assertThat(vo.getIngestSuccessRate()).isEqualTo(0.75);
    assertThat(vo.getTotalCost()).isEqualByComparingTo("5.00");
    assertThat(vo.getEmbeddingCost()).isEqualByComparingTo("1.25");
    assertThat(vo.getRerankCost()).isEqualByComparingTo("0.75");
    assertThat(vo.getLlmCost()).isEqualByComparingTo("3.00");
    assertThat(vo.getTokenTotal()).isEqualTo(350L);
    assertThat(vo.getRetrievalTrend()).hasSize(1);
    assertThat(vo.getRetrievalTrend().getFirst().getP95LatencyMs()).isEqualTo(120L);
    assertThat(vo.getRecentActivities()).isNotEmpty();
    assertThat(vo.getRecentActivities().stream().map(DashboardActivityVO::getType))
        .contains("index", "error", "eval", "search");
  }

  @Test
  void dashboard_usesCacheWithinTtl() {
    when(documentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    when(evalExperimentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
    when(evalExperimentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    when(retrievalLogMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

    metricsService.dashboard();
    metricsService.dashboard();

    verify(knowledgeBaseMapper, times(1)).selectCount(any(LambdaQueryWrapper.class));
    verify(documentMapper, times(1)).selectCount(any());
  }

  @Test
  void dashboard_handlesMissingEvalAndInvalidAvgLatency() {
    when(retrievalLogMapper.selectMaps(any()))
        .thenReturn(List.of(Map.of("avg_latency_ms", "bad")), List.of(), List.of());
    when(documentMapper.selectMaps(any())).thenReturn(null);
    when(modelUsageDailyMapper.aggregateCostSince(any(), any())).thenReturn(null);
    when(documentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    when(evalExperimentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
    when(evalExperimentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    when(retrievalLogMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

    DashboardMetricsVO vo = metricsService.dashboard();

    assertThat(vo.getHitRate()).isZero();
    assertThat(vo.getAvgLatencyMs()).isZero();
    verify(retrievalLogMapper, atLeastOnce()).selectMaps(any());
  }

  @Test
  void dashboard_orgScopeCountsQualityCostTrendAndActivities() {
    AdminOverrideHolder.clear();
    RagAuthContextHolder.set(
        new RagAuthContext(2L, "USER", Set.of(16L), Set.of(16L), Set.of(), "USER", "2"));
    com.ragforge.security.OrgContextHolder.set(88L);
    KnowledgeBase kb16 = kb(16L, "kb-16");
    KnowledgeBase kb17 = kb(17L, "kb-17");
    when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class)))
        .thenReturn(List.of(kb16, kb17), List.of(kb16, kb17));
    when(documentMapper.selectCount(any())).thenReturn(6L);
    when(documentChunkMapper.selectCount(any())).thenReturn(60L);
    when(retrievalLogMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(11L);
    when(retrievalLogMapper.selectMaps(any()))
        .thenReturn(
            List.of(Map.of("avg_latency_ms", "125.4")),
            List.of(
                Map.of(
                    "total",
                    12,
                    "success_cnt",
                    9,
                    "zero_cnt",
                    3,
                    "avg_recall",
                    "5.5",
                    "p50",
                    "80",
                    "p95",
                    "180.9",
                    "rewrite_cnt",
                    6,
                    "avg_rerank",
                    "0.76")),
            List.of(Map.of("d", "06-30", "cnt", 12, "p95", "181.2")));
    when(documentMapper.selectMaps(any()))
        .thenReturn(
            List.of(
                Map.of("st", "completed", "cnt", 4),
                Map.of("st", "failed", "cnt", 1),
                Map.of("st", "pending", "cnt", 2),
                Map.of("st", "processing", "cnt", 3)));
    when(modelUsageDailyMapper.aggregateCostSince(any(), any()))
        .thenReturn(
            List.of(
                Map.of("purpose", "embedding", "tokens", "100", "cost", "1.00"),
                Map.of("purpose", "judge", "tokens", 300, "cost", new BigDecimal("4.50"))));
    Document pending = document(10L, 16L, "pending.pdf", "queued");
    Document failed = document(11L, 17L, "failed.pdf", "FAILED");
    when(documentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pending, failed));
    RetrievalLog log = new RetrievalLog();
    log.setStrategy("hybrid");
    log.setLatencyMs(77);
    log.setCreatedAt(LocalDateTime.now());
    when(retrievalLogMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(log));

    DashboardMetricsVO vo = metricsService.dashboard();

    assertThat(vo.getKbCount()).isEqualTo(2L);
    assertThat(vo.getDocumentCount()).isEqualTo(6L);
    assertThat(vo.getChunkCount()).isEqualTo(60L);
    assertThat(vo.getTodayApiCalls()).isEqualTo(11L);
    assertThat(vo.getAvgLatencyMs()).isEqualTo(125L);
    assertThat(vo.getHitRate()).isZero();
    assertThat(vo.getSearchSuccessRate()).isEqualTo(0.75);
    assertThat(vo.getZeroResultRate()).isEqualTo(1.0 / 3.0);
    assertThat(vo.getAvgRecallCount()).isEqualTo(5.5);
    assertThat(vo.getP95LatencyMs()).isEqualTo(181L);
    assertThat(vo.getRewriteRate()).isEqualTo(2.0 / 3.0);
    assertThat(vo.getIngestSuccessRate()).isEqualTo(0.8);
    assertThat(vo.getTotalCost()).isEqualByComparingTo("5.50");
    assertThat(vo.getTokenTotal()).isEqualTo(400L);
    assertThat(vo.getRecentActivities().stream().map(DashboardActivityVO::getType))
        .contains("upload", "error", "search");
    assertThat(vo.getRecentActivities().stream().filter(a -> "error".equals(a.getType())).findFirst())
        .get()
        .extracting(DashboardActivityVO::getRetryable)
        .isEqualTo(true);
  }

  private static Document document(long id, long kbId, String name, String status) {
    Document doc = new Document();
    doc.setId(id);
    doc.setKbId(kbId);
    doc.setFilename(name);
    doc.setParseStatus(status);
    doc.setCreatedAt(LocalDateTime.now());
    return doc;
  }

  private static KnowledgeBase kb(long id, String name) {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(id);
    kb.setName(name);
    return kb;
  }

  private static EvalExperiment experiment(
      long id, long datasetId, String strategy, String status, BigDecimal top3) {
    EvalExperiment exp = new EvalExperiment();
    exp.setId(id);
    exp.setDatasetId(datasetId);
    exp.setStrategy(strategy);
    exp.setStatus(status);
    exp.setTop3HitRate(top3);
    exp.setCreatedAt(LocalDateTime.now());
    return exp;
  }

  private static EvalDataset dataset(long id, String name) {
    EvalDataset ds = new EvalDataset();
    ds.setId(id);
    ds.setName(name);
    return ds;
  }
}
