package com.ragforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.EvalDatasetMapper;
import com.ragforge.mapper.EvalExperimentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.mapper.ModelUsageDailyMapper;
import com.ragforge.mapper.RetrievalLogMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.model.entity.EvalDataset;
import com.ragforge.model.entity.EvalExperiment;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.model.entity.RetrievalLog;
import com.ragforge.model.vo.DashboardActivityVO;
import com.ragforge.model.vo.DashboardMetricsVO;
import com.ragforge.model.vo.DashboardTrendPointVO;
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
  /** 核心指标统计窗口：近 7 天（含今天，共 7 个自然日）。 */
  private static final int WINDOW_DAYS = 7;

  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final DocumentMapper documentMapper;
  private final DocumentChunkMapper documentChunkMapper;
  private final RetrievalLogMapper retrievalLogMapper;
  private final ModelUsageDailyMapper modelUsageDailyMapper;
  private final EvalExperimentMapper evalExperimentMapper;
  private final EvalDatasetMapper evalDatasetMapper;
  // 缓存按主体分键：管理员看全平台，普通用户看自己的数据，二者不可串用同一份缓存。
  private final Map<String, DashboardCache> dashboardCacheByPrincipal = new ConcurrentHashMap<>();

  @Override
  public DashboardMetricsVO dashboard() {
    RagAuthContext ctx = RagAuthContextHolder.get();
    // 管理员默认只看自己的范围（与知识库访问一致）；仅在破玻璃(X-Admin-Override)时才看全平台。
    boolean fullPlatform = ctx != null && ctx.isAdmin() && AdminOverrideHolder.isActive();
    Long currentOrgId = com.ragforge.security.OrgContextHolder.get();
    String key = fullPlatform ? "ADMIN" : ("O:" + currentOrgId);

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
      DashboardMetricsVO vo = fullPlatform ? loadAdminDashboard() : loadOrgDashboard(currentOrgId);
      dashboardCacheByPrincipal.put(key, new DashboardCache(vo, now + DASHBOARD_CACHE_TTL_MS));
      return vo;
    }
  }

  /** 按当前组织(X-Org-Id)：知识库/文档/chunk 仅统计本组织的库；检索次数/延迟/质量按 org_id 归属本组织。 */
  private DashboardMetricsVO loadOrgDashboard(Long orgId) {
    DashboardMetricsVO vo = new DashboardMetricsVO();
    List<Long> kbIds = orgId == null ? List.of() : orgKbIds(orgId);
    vo.setKbCount(kbIds.size());
    if (kbIds.isEmpty()) {
      vo.setDocumentCount(0);
      vo.setChunkCount(0);
    } else {
      vo.setDocumentCount(
          documentMapper.selectCount(
              new LambdaQueryWrapper<Document>().in(Document::getKbId, kbIds)));
      vo.setChunkCount(
          documentChunkMapper.selectCount(
              new LambdaQueryWrapper<DocumentChunk>().in(DocumentChunk::getKbId, kbIds)));
    }

    LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
    LocalDateTime windowStart = windowStart();
    if (orgId == null) {
      vo.setTodayApiCalls(0);
      vo.setAvgLatencyMs(0);
    } else {
      vo.setTodayApiCalls(
          retrievalLogMapper.selectCount(
              new LambdaQueryWrapper<RetrievalLog>()
                  .eq(RetrievalLog::getOrgId, orgId)
                  .ge(RetrievalLog::getCreatedAt, startOfDay)));
      vo.setAvgLatencyMs(calcAvgLatencyMs(windowStart, orgId));
    }
    applyRetrievalQuality(vo, windowStart, orgId);
    applyIngestHealth(vo, kbIds, false);
    applyCost(vo, orgId);
    vo.setRetrievalTrend(loadRetrievalTrend(windowStart, orgId));
    // 命中率源自评测实验（管理员/编辑功能），普通用户无评测，不展示。
    vo.setHitRate(0.0);
    vo.setRecentActivities(orgId == null ? List.of() : orgRecentActivities(orgId, kbIds, 10));
    return vo;
  }

  private List<Long> orgKbIds(Long orgId) {
    return knowledgeBaseMapper
        .selectList(
            new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getOrgId, orgId)
                .ne(KnowledgeBase::getStatus, KB_STATUS_DELETED))
        .stream()
        .map(KnowledgeBase::getId)
        .toList();
  }

  /** 本组织的"最近操作"：组织库文档动态 + 本组织发起的检索；不含他组织操作与评测。 */
  private List<DashboardActivityVO> orgRecentActivities(Long orgId, List<Long> kbIds, int limit) {
    DateTimeFormatter tf = DateTimeFormatter.ofPattern("HH:mm");
    List<ActivityEntry> entries = new ArrayList<>();

    if (!kbIds.isEmpty()) {
      List<Document> recentDocs =
          documentMapper.selectList(
              new LambdaQueryWrapper<Document>()
                  .in(Document::getKbId, kbIds)
                  .orderByDesc(Document::getCreatedAt)
                  .last("LIMIT " + limit));
      Map<Long, String> kbNameMap = loadKbNames(recentDocs.stream().map(Document::getKbId).toList());
      for (Document doc : recentDocs) {
        // parse_status 历史大小写混用，统一归一化比较（此前大写 COMPLETED 漏判）。
        String st =
            doc.getParseStatus() == null
                ? ""
                : doc.getParseStatus().toLowerCase(java.util.Locale.ROOT);
        DashboardActivityVO vo = new DashboardActivityVO();
        vo.setTime(doc.getCreatedAt() != null ? doc.getCreatedAt().format(tf) : "--:--");
        String kbName = kbNameMap.getOrDefault(doc.getKbId(), String.valueOf(doc.getKbId()));
        switch (st) {
          case "completed" -> {
            vo.setType("index");
            vo.setMessage(String.format("知识库「%s」文档「%s」处理完成", kbName, doc.getFilename()));
          }
          case "failed" -> {
            vo.setType("error");
            vo.setMessage(String.format("文档「%s」解析失败", doc.getFilename()));
            vo.setDocId(doc.getId());
            vo.setRetryable(true);
          }
          default -> {
            // pending/queued/processing/reprocessing → 上传后处理中，立即可见以给用户反馈。
            vo.setType("upload");
            vo.setMessage(String.format("知识库「%s」上传文档「%s」，处理中", kbName, doc.getFilename()));
          }
        }
        entries.add(new ActivityEntry(doc.getCreatedAt(), vo));
      }
    }

    List<RetrievalLog> myLogs =
        retrievalLogMapper.selectList(
            new LambdaQueryWrapper<RetrievalLog>()
                .eq(RetrievalLog::getOrgId, orgId)
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
    LocalDateTime windowStart = windowStart();
    long todayApiCalls =
        retrievalLogMapper.selectCount(
            new LambdaQueryWrapper<RetrievalLog>().ge(RetrievalLog::getCreatedAt, startOfDay));

    long avgLatencyMs = calcAvgLatencyMs(windowStart);
    double hitRate = latestTop3HitRate();

    vo.setKbCount(kbCount);
    vo.setDocumentCount(documentCount);
    vo.setChunkCount(chunkCount);
    vo.setTodayApiCalls(todayApiCalls);
    vo.setAvgLatencyMs(avgLatencyMs);
    vo.setHitRate(hitRate);
    applyRetrievalQuality(vo, windowStart, null);
    applyIngestHealth(vo, List.of(), true);
    applyCost(vo, null);
    vo.setRetrievalTrend(loadRetrievalTrend(windowStart, null));
    vo.setRecentActivities(getRecentActivities(10));
    return vo;
  }

  /**
   * 检索质量/健康指标：全部由 retrieval_logs 现有列直接聚合，无需改表。 uid=null 表示全平台（管理员破玻璃）。
   * 零结果率 = result_count=0 占比；平均召回 = avg(result_count)；P95 = percentile_cont；改写率 =
   * rewritten_queries 非空占比。
   */
  private void applyRetrievalQuality(DashboardMetricsVO vo, LocalDateTime startOfDay, Long orgId) {
    QueryWrapper<RetrievalLog> wrapper = new QueryWrapper<>();
    wrapper.select(
        "count(*) as total",
        "count(*) filter (where status = 'SUCCESS') as success_cnt",
        "count(*) filter (where status = 'SUCCESS' and result_count = 0) as zero_cnt",
        "avg(result_count) filter (where status = 'SUCCESS') as avg_recall",
        "percentile_cont(0.5) within group (order by latency_ms) filter (where status = 'SUCCESS') as p50",
        "percentile_cont(0.95) within group (order by latency_ms) filter (where status = 'SUCCESS') as p95",
        "count(*) filter (where status = 'SUCCESS' and rewritten_queries is not null and rewritten_queries <> '') as rewrite_cnt",
        "avg(avg_rerank_score) filter (where avg_rerank_score is not null) as avg_rerank");
    wrapper.ge("created_at", startOfDay);
    if (orgId != null) {
      wrapper.eq("org_id", orgId);
    }
    List<Map<String, Object>> rows = retrievalLogMapper.selectMaps(wrapper);
    if (rows == null || rows.isEmpty() || rows.get(0) == null) {
      return;
    }
    Map<String, Object> r = rows.get(0);
    long total = asLong(r.get("total"));
    vo.setPeriodApiCalls(total);
    if (total <= 0) {
      return;
    }
    long success = asLong(r.get("success_cnt"));
    vo.setSearchSuccessRate((double) success / total);
    double avgRerank = asDouble(r.get("avg_rerank"));
    if (avgRerank > 0) {
      vo.setAvgRerankScore(avgRerank);
    }
    // 质量指标（零结果/召回/延迟/改写）只在成功样本上计算，避免错误请求拉偏。
    if (success <= 0) {
      return;
    }
    vo.setZeroResultRate(asDouble(r.get("zero_cnt")) / success);
    vo.setAvgRecallCount(asDouble(r.get("avg_recall")));
    vo.setP50LatencyMs(Math.round(asDouble(r.get("p50"))));
    vo.setP95LatencyMs(Math.round(asDouble(r.get("p95"))));
    vo.setRewriteRate(asDouble(r.get("rewrite_cnt")) / success);
  }

  /** 近 7 天窗口起点（含今天，共 WINDOW_DAYS 个自然日）。 */
  private LocalDateTime windowStart() {
    return LocalDate.now().minusDays(WINDOW_DAYS - 1L).atStartOfDay();
  }

  /**
   * 入库处理健康度：按 documents.parse_status 分桶（completed/processing/queued/failed）。
   * parse_status 历史大小写混用，统一 lower() 归一；allScope=true 统计全平台，否则限定 kbScope。
   */
  private void applyIngestHealth(DashboardMetricsVO vo, List<Long> kbScope, boolean allScope) {
    QueryWrapper<Document> w = new QueryWrapper<>();
    w.select("lower(parse_status) as st", "count(*) as cnt");
    if (!allScope) {
      if (kbScope == null || kbScope.isEmpty()) {
        return;
      }
      w.in("kb_id", kbScope);
    }
    w.groupBy("lower(parse_status)");
    List<Map<String, Object>> rows = documentMapper.selectMaps(w);
    if (rows == null) {
      return;
    }
    long completed = 0;
    long processing = 0;
    long queued = 0;
    long failed = 0;
    for (Map<String, Object> row : rows) {
      String st = row.get("st") == null ? "" : row.get("st").toString();
      long cnt = asLong(row.get("cnt"));
      switch (st) {
        case "completed" -> completed += cnt;
        case "failed" -> failed += cnt;
        case "processing", "reprocessing" -> processing += cnt;
        case "pending", "queued" -> queued += cnt;
        default -> {
          // 未知状态忽略，不计入任何桶。
        }
      }
    }
    vo.setIngestCompleted(completed);
    vo.setIngestProcessing(processing);
    vo.setIngestQueued(queued);
    vo.setIngestFailed(failed);
    long terminal = completed + failed;
    vo.setIngestSuccessRate(terminal > 0 ? (double) completed / terminal : 0.0);
  }

  /** 近 7 天成本：按用途聚合 model_usage_daily。orgId=null 为全平台（管理员破玻璃），否则限定本组织。 */
  private void applyCost(DashboardMetricsVO vo, Long orgId) {
    List<Map<String, Object>> rows =
        modelUsageDailyMapper.aggregateCostSince(LocalDate.now().minusDays(WINDOW_DAYS - 1L), orgId);
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal embedding = BigDecimal.ZERO;
    BigDecimal rerank = BigDecimal.ZERO;
    BigDecimal llm = BigDecimal.ZERO;
    long tokens = 0;
    if (rows != null) {
      for (Map<String, Object> row : rows) {
        String purpose = row.get("purpose") == null ? "" : row.get("purpose").toString();
        BigDecimal cost = asBigDecimal(row.get("cost"));
        tokens += asLong(row.get("tokens"));
        total = total.add(cost);
        switch (purpose) {
          case "embedding" -> embedding = embedding.add(cost);
          case "rerank" -> rerank = rerank.add(cost);
          // OCR/REWRITE/ANSWER/JUDGE 归入 LLM 大类，保证 total = embedding + rerank + llm。
          default -> llm = llm.add(cost);
        }
      }
    }
    vo.setTotalCost(total);
    vo.setTokenTotal(tokens);
    vo.setEmbeddingCost(embedding);
    vo.setRerankCost(rerank);
    vo.setLlmCost(llm);
  }

  /** 近 7 天检索趋势：按自然日聚合请求数与 P95；无数据的日子不补零，由前端按返回点渲染。 */
  private List<DashboardTrendPointVO> loadRetrievalTrend(LocalDateTime windowStart, Long orgId) {
    QueryWrapper<RetrievalLog> w = new QueryWrapper<>();
    w.select(
        "to_char(created_at, 'MM-DD') as d",
        "count(*) as cnt",
        "percentile_cont(0.95) within group (order by latency_ms) as p95");
    w.ge("created_at", windowStart);
    if (orgId != null) {
      w.eq("org_id", orgId);
    }
    w.groupBy("to_char(created_at, 'MM-DD'), date(created_at)");
    w.last("ORDER BY date(created_at)");
    List<Map<String, Object>> rows = retrievalLogMapper.selectMaps(w);
    if (rows == null) {
      return List.of();
    }
    List<DashboardTrendPointVO> trend = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      DashboardTrendPointVO p = new DashboardTrendPointVO();
      p.setDate(row.get("d") == null ? "" : row.get("d").toString());
      p.setCount(asLong(row.get("cnt")));
      p.setP95LatencyMs(Math.round(asDouble(row.get("p95"))));
      trend.add(p);
    }
    return trend;
  }

  private static BigDecimal asBigDecimal(Object o) {
    if (o instanceof BigDecimal b) return b;
    if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
    try {
      return o == null ? BigDecimal.ZERO : new BigDecimal(o.toString());
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }

  private static long asLong(Object o) {
    if (o instanceof Number n) return n.longValue();
    try {
      return o == null ? 0L : Math.round(Double.parseDouble(o.toString()));
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  private static double asDouble(Object o) {
    if (o instanceof Number n) return n.doubleValue();
    try {
      return o == null ? 0d : Double.parseDouble(o.toString());
    } catch (NumberFormatException e) {
      return 0d;
    }
  }

  private long calcAvgLatencyMs(LocalDateTime startOfDay) {
    return calcAvgLatencyMs(startOfDay, null);
  }

  private long calcAvgLatencyMs(LocalDateTime startOfDay, Long orgId) {
    QueryWrapper<RetrievalLog> wrapper = new QueryWrapper<>();
    wrapper.select("avg(latency_ms) as avg_latency_ms");
    wrapper.ge("created_at", startOfDay);
    if (orgId != null) {
      wrapper.eq("org_id", orgId);
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
      // parse_status 大小写归一化（此前 COMPLETED 漏判）。
      String st =
          doc.getParseStatus() == null
              ? ""
              : doc.getParseStatus().toLowerCase(java.util.Locale.ROOT);
      DashboardActivityVO vo = new DashboardActivityVO();
      vo.setTime(doc.getCreatedAt() != null ? doc.getCreatedAt().format(tf) : "--:--");
      String kbName = kbNameMap.getOrDefault(doc.getKbId(), String.valueOf(doc.getKbId()));
      switch (st) {
        case "completed" -> {
          vo.setType("index");
          vo.setMessage(String.format("知识库「%s」文档「%s」处理完成", kbName, doc.getFilename()));
        }
        case "failed" -> {
          vo.setType("error");
          vo.setMessage(String.format("文档「%s」解析失败", doc.getFilename()));
          vo.setDocId(doc.getId());
          vo.setRetryable(true);
        }
        default -> {
          vo.setType("upload");
          vo.setMessage(String.format("知识库「%s」上传文档「%s」，处理中", kbName, doc.getFilename()));
        }
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
