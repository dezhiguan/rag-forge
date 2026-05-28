package com.ragforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.EvalExperimentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.mapper.RetrievalLogMapper;
import com.ragforge.model.entity.EvalExperiment;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.model.entity.RetrievalLog;
import com.ragforge.model.vo.DashboardMetricsVO;
import com.ragforge.service.MetricsService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
}

