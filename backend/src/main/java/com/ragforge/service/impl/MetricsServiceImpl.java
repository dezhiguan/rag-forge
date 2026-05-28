package com.ragforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import com.ragforge.service.MetricsService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MetricsServiceImpl implements MetricsService {

  private static final String KB_STATUS_DELETED = "deleted";

  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final DocumentMapper documentMapper;
  private final DocumentChunkMapper documentChunkMapper;
  private final RetrievalLogMapper retrievalLogMapper;
  private final EvalExperimentMapper evalExperimentMapper;
  private final EvalDatasetMapper evalDatasetMapper;

  @Override
  public DashboardMetricsVO dashboard() {
    DashboardMetricsVO vo = new DashboardMetricsVO();

    long kbCount =
        knowledgeBaseMapper.selectCount(
            new LambdaQueryWrapper<KnowledgeBase>().ne(KnowledgeBase::getStatus, KB_STATUS_DELETED));
    long documentCount = documentMapper.selectCount(null);
    long chunkCount = documentChunkMapper.selectCount(null);

    LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
    long todayApiCalls =
        retrievalLogMapper.selectCount(
            new LambdaQueryWrapper<RetrievalLog>().ge(RetrievalLog::getCreatedAt, startOfDay));

    long avgLatencyMs = calcAvgLatencyMs(startOfDay);
    double hitRate = latestTop3HitRate();

    vo.setKbCount(kbCount);
    vo.setDocumentCount(documentCount);
    vo.setChunkCount(chunkCount);
    vo.setTodayApiCalls(todayApiCalls);
    vo.setAvgLatencyMs(avgLatencyMs);
    vo.setHitRate(hitRate);
    vo.setRecentActivities(getRecentActivities(10));
    return vo;
  }

  private long calcAvgLatencyMs(LocalDateTime startOfDay) {
    QueryWrapper<RetrievalLog> wrapper = new QueryWrapper<>();
    wrapper.select("avg(latency_ms) as avg_latency_ms");
    wrapper.ge("created_at", startOfDay);
    List<Map<String, Object>> rows = retrievalLogMapper.selectMaps(wrapper);
    if (rows == null || rows.isEmpty()) return 0;
    Map<String, Object> row = rows.get(0);
    if (row == null) return 0;
    Object avg = row.get("avg_latency_ms");
    if (avg == null) return 0;
    if (avg instanceof Number n) {
      return Math.round(n.doubleValue());
    }
    try {
      return Math.round(Double.parseDouble(avg.toString()));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private double latestTop3HitRate() {
    EvalExperiment latest =
        evalExperimentMapper.selectOne(
            new LambdaQueryWrapper<EvalExperiment>()
                .orderByDesc(EvalExperiment::getCreatedAt)
                .last("LIMIT 1"));
    if (latest == null) return 0.0;
    BigDecimal rate = latest.getTop3HitRate();
    if (rate == null) return 0.0;
    return rate.doubleValue();
  }

  private List<DashboardActivityVO> getRecentActivities(int limit) {
    List<ActivityEntry> entries = new ArrayList<>();
    DateTimeFormatter tf = DateTimeFormatter.ofPattern("HH:mm");

    List<Document> recentDocs =
        documentMapper.selectList(
            new LambdaQueryWrapper<Document>().orderByDesc(Document::getCreatedAt).last("LIMIT 5"));
    Map<Long, String> kbNameMap = loadKbNames(recentDocs.stream().map(Document::getKbId).toList());
    for (Document doc : recentDocs) {
      if (!"completed".equals(doc.getParseStatus()) && !"failed".equals(doc.getParseStatus())) {
        continue;
      }
      DashboardActivityVO vo = new DashboardActivityVO();
      vo.setTime(doc.getCreatedAt() != null ? doc.getCreatedAt().format(tf) : "--:--");
      if ("completed".equals(doc.getParseStatus())) {
        vo.setType("index");
        String kbName = kbNameMap.getOrDefault(doc.getKbId(), String.valueOf(doc.getKbId()));
        vo.setMessage(String.format("知识库「%s」文档「%s」处理完成", kbName, doc.getFilename()));
      } else {
        vo.setType("error");
        vo.setMessage(String.format("文档「%s」解析失败", doc.getFilename()));
        vo.setDocId(doc.getId());
        vo.setRetryable(true);
      }
      entries.add(new ActivityEntry(doc.getCreatedAt(), vo));
    }

    List<EvalExperiment> recentExperiments =
        evalExperimentMapper.selectList(
            new LambdaQueryWrapper<EvalExperiment>().orderByDesc(EvalExperiment::getCreatedAt).last("LIMIT 3"));
    Map<Long, String> dsNameMap = loadDatasetNames(recentExperiments.stream().map(EvalExperiment::getDatasetId).toList());
    for (EvalExperiment exp : recentExperiments) {
      if (!"completed".equals(exp.getStatus())) {
        continue;
      }
      DashboardActivityVO vo = new DashboardActivityVO();
      vo.setTime(exp.getCreatedAt() != null ? exp.getCreatedAt().format(tf) : "--:--");
      vo.setType("eval");
      String dsName = dsNameMap.getOrDefault(exp.getDatasetId(), String.valueOf(exp.getDatasetId()));
      double top3 = exp.getTop3HitRate() == null ? 0.0 : exp.getTop3HitRate().doubleValue() * 100;
      vo.setMessage(String.format("评测实验「%s-%s」完成，Top3: %.1f%%", dsName, exp.getStrategy(), top3));
      entries.add(new ActivityEntry(exp.getCreatedAt(), vo));
    }

    List<RetrievalLog> recentLogs =
        retrievalLogMapper.selectList(
            new LambdaQueryWrapper<RetrievalLog>().orderByDesc(RetrievalLog::getCreatedAt).last("LIMIT 2"));
    for (RetrievalLog log : recentLogs) {
      DashboardActivityVO vo = new DashboardActivityVO();
      vo.setTime(log.getCreatedAt() != null ? log.getCreatedAt().format(tf) : "--:--");
      vo.setType("search");
      vo.setMessage(
          String.format("检索请求完成，策略: %s，耗时: %sms", log.getStrategy(), log.getLatencyMs()));
      entries.add(new ActivityEntry(log.getCreatedAt(), vo));
    }

    return entries.stream()
        .sorted(Comparator.comparing(ActivityEntry::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
        .limit(limit)
        .map(ActivityEntry::activity)
        .toList();
  }

  private Map<Long, String> loadKbNames(List<Long> kbIds) {
    if (kbIds == null || kbIds.isEmpty()) return Map.of();
    return knowledgeBaseMapper
        .selectList(new LambdaQueryWrapper<KnowledgeBase>().in(KnowledgeBase::getId, kbIds))
        .stream()
        .collect(HashMap::new, (m, kb) -> m.put(kb.getId(), kb.getName()), HashMap::putAll);
  }

  private Map<Long, String> loadDatasetNames(List<Long> datasetIds) {
    if (datasetIds == null || datasetIds.isEmpty()) return Map.of();
    return evalDatasetMapper
        .selectList(new LambdaQueryWrapper<EvalDataset>().in(EvalDataset::getId, datasetIds))
        .stream()
        .collect(HashMap::new, (m, ds) -> m.put(ds.getId(), ds.getName()), HashMap::putAll);
  }

  private record ActivityEntry(LocalDateTime createdAt, DashboardActivityVO activity) {}
}

