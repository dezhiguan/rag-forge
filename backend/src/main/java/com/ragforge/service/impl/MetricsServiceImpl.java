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
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.model.entity.EvalDataset;
import com.ragforge.model.entity.EvalExperiment;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.model.entity.RetrievalLog;
import com.ragforge.model.vo.DashboardActivityVO;
import com.ragforge.model.vo.DashboardMetricsVO;
import com.ragforge.security.AdminOverrideHolder;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
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
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MetricsServiceImpl implements MetricsService {

  private static final String KB_STATUS_DELETED = "deleted";
  private static final long DASHBOARD_CACHE_TTL_MS = 10_000L;

  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final DocumentMapper documentMapper;
  private final DocumentChunkMapper documentChunkMapper;
  private final RetrievalLogMapper retrievalLogMapper;
  private final EvalExperimentMapper evalExperimentMapper;
  private final EvalDatasetMapper evalDatasetMapper;
  // 缓存按主体分键：管理员看全平台，普通用户看自己的数据，二者不可串用同一份缓存。
  private final Map<String, DashboardCache> dashboardCacheByPrincipal = new ConcurrentHashMap<>();

  @Override
  public DashboardMetricsVO dashboard() {
    RagAuthContext ctx = RagAuthContextHolder.get();
    // 管理员默认只看自己的范围（与知识库访问一致）；仅在破玻璃(X-Admin-Override)时才看全平台。
    boolean fullPlatform = ctx != null && ctx.isAdmin() && AdminOverrideHolder.isActive();
    Long uid = ctx == null ? null : ctx.userId();
    String key = fullPlatform ? "ADMIN" : ("U:" + uid);

    long now = System.currentTimeMillis();
    DashboardCache cached = dashboardCacheByPrincipal.get(key);
    if (cached != null && now < cached.expiresAtMs()) {
      return cached.value();
    }
    synchronized (this) {
      cached = dashboardCacheByPrincipal.get(key);
      now = System.currentTimeMillis();
      if (cached != null && now < cached.expiresAtMs()) {
        return cached.value();
      }
      DashboardMetricsVO vo = fullPlatform ? loadAdminDashboard() : loadUserDashboard(uid);
      dashboardCacheByPrincipal.put(key, new DashboardCache(vo, now + DASHBOARD_CACHE_TTL_MS));
      return vo;
    }
  }

  /** 普通用户：知识库/文档/chunk 仅统计自己拥有的库；检索次数/延迟按 user_id 精确归属本人。 */
  private DashboardMetricsVO loadUserDashboard(Long uid) {
    DashboardMetricsVO vo = new DashboardMetricsVO();
    List<Long> ownedIds = uid == null ? List.of() : ownedKbIds(uid);
    vo.setKbCount(ownedIds.size());
    if (ownedIds.isEmpty()) {
      vo.setDocumentCount(0);
      vo.setChunkCount(0);
    } else {
      vo.setDocumentCount(
          documentMapper.selectCount(
              new LambdaQueryWrapper<Document>().in(Document::getKbId, ownedIds)));
      vo.setChunkCount(
          documentChunkMapper.selectCount(
              new LambdaQueryWrapper<DocumentChunk>().in(DocumentChunk::getKbId, ownedIds)));
    }

    LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
    if (uid == null) {
      vo.setTodayApiCalls(0);
      vo.setAvgLatencyMs(0);
    } else {
      vo.setTodayApiCalls(
          retrievalLogMapper.selectCount(
              new LambdaQueryWrapper<RetrievalLog>()
                  .eq(RetrievalLog::getUserId, uid)
                  .ge(RetrievalLog::getCreatedAt, startOfDay)));
      vo.setAvgLatencyMs(calcAvgLatencyMs(startOfDay, uid));
    }
    // 命中率源自评测实验（管理员/编辑功能），普通用户无评测，不展示。
    vo.setHitRate(0.0);
    vo.setRecentActivities(uid == null ? List.of() : userRecentActivities(uid, ownedIds, 10));
    return vo;
  }

  private List<Long> ownedKbIds(Long uid) {
    return knowledgeBaseMapper
        .selectList(
            new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getOwnerUserId, uid)
                .ne(KnowledgeBase::getStatus, KB_STATUS_DELETED))
        .stream()
        .map(KnowledgeBase::getId)
        .toList();
  }

  /** 普通用户的"最近操作"：自有库文档动态 + 本人发起的检索；不含他人操作与评测。 */
  private List<DashboardActivityVO> userRecentActivities(Long uid, List<Long> ownedIds, int limit) {
    DateTimeFormatter tf = DateTimeFormatter.ofPattern("HH:mm");
    List<ActivityEntry> entries = new ArrayList<>();

    if (!ownedIds.isEmpty()) {
      List<Document> recentDocs =
          documentMapper.selectList(
              new LambdaQueryWrapper<Document>()
                  .in(Document::getKbId, ownedIds)
                  .orderByDesc(Document::getCreatedAt)
                  .last("LIMIT " + limit));
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
    }

    List<RetrievalLog> myLogs =
        retrievalLogMapper.selectList(
            new LambdaQueryWrapper<RetrievalLog>()
                .eq(RetrievalLog::getUserId, uid)
                .orderByDesc(RetrievalLog::getCreatedAt)
                .last("LIMIT " + limit));
    for (RetrievalLog log : myLogs) {
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

  private DashboardMetricsVO loadAdminDashboard() {
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
    return calcAvgLatencyMs(startOfDay, null);
  }

  private long calcAvgLatencyMs(LocalDateTime startOfDay, Long uid) {
    QueryWrapper<RetrievalLog> wrapper = new QueryWrapper<>();
    wrapper.select("avg(latency_ms) as avg_latency_ms");
    wrapper.ge("created_at", startOfDay);
    if (uid != null) {
      wrapper.eq("user_id", uid);
    }
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
    // 取最近一次评测的数据集，返回该数据集下所有策略中的最优 Top3 命中率
    EvalExperiment latest =
        evalExperimentMapper.selectOne(
            new LambdaQueryWrapper<EvalExperiment>()
                .orderByDesc(EvalExperiment::getCreatedAt)
                .last("LIMIT 1"));
    if (latest == null || latest.getDatasetId() == null) return 0.0;

    List<EvalExperiment> sameDataset =
        evalExperimentMapper.selectList(
            new LambdaQueryWrapper<EvalExperiment>()
                .eq(EvalExperiment::getDatasetId, latest.getDatasetId())
                .isNotNull(EvalExperiment::getTop3HitRate));
    if (sameDataset.isEmpty()) return 0.0;

    return sameDataset.stream()
        .mapToDouble(e -> e.getTop3HitRate() == null ? 0.0 : e.getTop3HitRate().doubleValue())
        .max()
        .orElse(0.0);
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

  private record DashboardCache(DashboardMetricsVO value, long expiresAtMs) {}
}
