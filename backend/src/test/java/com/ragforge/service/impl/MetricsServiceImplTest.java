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
import com.ragforge.mapper.RetrievalLogMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.EvalDataset;
import com.ragforge.model.entity.EvalExperiment;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.model.entity.RetrievalLog;
import com.ragforge.model.vo.DashboardActivityVO;
import com.ragforge.model.vo.DashboardMetricsVO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MetricsServiceImplTest {

  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private DocumentMapper documentMapper;
  @Mock private DocumentChunkMapper documentChunkMapper;
  @Mock private RetrievalLogMapper retrievalLogMapper;
  @Mock private EvalExperimentMapper evalExperimentMapper;
  @Mock private EvalDatasetMapper evalDatasetMapper;

  @InjectMocks private MetricsServiceImpl metricsService;

  @BeforeEach
  void stubCounts() {
    when(knowledgeBaseMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);
    when(documentMapper.selectCount(any())).thenReturn(10L);
    when(documentChunkMapper.selectCount(any())).thenReturn(100L);
    when(retrievalLogMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L);
    when(retrievalLogMapper.selectMaps(any())).thenReturn(List.of(Map.of("avg_latency_ms", 42.6)));
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
    when(retrievalLogMapper.selectMaps(any())).thenReturn(List.of(Map.of("avg_latency_ms", "bad")));
    when(documentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    when(evalExperimentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
    when(evalExperimentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
    when(retrievalLogMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

    DashboardMetricsVO vo = metricsService.dashboard();

    assertThat(vo.getHitRate()).isZero();
    assertThat(vo.getAvgLatencyMs()).isZero();
    verify(retrievalLogMapper, atLeastOnce()).selectMaps(any());
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
