package com.ragforge.service.impl;

import static java.math.RoundingMode.HALF_UP;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.answer.AnswerModels.Citation;
import com.ragforge.common.BizException;
import com.ragforge.mapper.AnswerLogMapper;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.JudgeResultMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.entity.AnswerLog;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.model.entity.JudgeResult;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.model.vo.AnomalyVo;
import com.ragforge.model.vo.CaseDetailVo;
import com.ragforge.model.vo.ChunkSnapshotVo;
import com.ragforge.model.vo.CostSummaryVo;
import com.ragforge.model.vo.KbSliceVo;
import com.ragforge.model.vo.KpiVo;
import com.ragforge.model.vo.OverviewVo;
import com.ragforge.model.vo.SampleStatsVo;
import com.ragforge.model.vo.TrendPointVo;
import com.ragforge.model.vo.WorstCaseVo;
import com.ragforge.service.JudgeQueryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

@Service
@RequiredArgsConstructor
public class JudgeQueryServiceImpl implements JudgeQueryService {

  private static final int DEFAULT_DAYS = 7;
  private static final int DEFAULT_LIMIT = 10;
  private static final int DEFAULT_SCALE = 4;
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private static final TypeReference<List<Citation>> CITATION_LIST_TYPE = new TypeReference<>() {};

  private final JdbcTemplate jdbcTemplate;
  private final JudgeResultMapper judgeResultMapper;
  private final AnswerLogMapper answerLogMapper;
  private final DocumentChunkMapper documentChunkMapper;
  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final ObjectMapper objectMapper;

  @Override
  public OverviewVo overview(int days, Long kbId) {
    int safeDays = normalizeDays(days);
    LocalDate today = LocalDate.now();
    LocalDate queryStart = today.minusDays(Math.max(0, safeDays * 2 - 1));

    List<Map<String, Object>> rows =
        (kbId == null)
            ? queryGlobalMetricsRows(queryStart)
            : queryKbMetricsRows(kbId, queryStart);

    TrendWindow current = aggregateWindow(rows, today.minusDays(safeDays - 1), today);
    TrendWindow previous = aggregateWindow(rows, today.minusDays(safeDays * 2 - 1), today.minusDays(safeDays));

    KpiVo kpi = new KpiVo();
    kpi.setOverallScore(current.overall());
    kpi.setFaithfulness(current.faithfulness());
    kpi.setContextPrecision(current.contextPrecision());
    kpi.setAnswerRelevance(current.answerRelevance());
    kpi.setCostLastPeriodCny(current.totalCost());
    kpi.setRetrievalLatencyP95Ms(0);
    kpi.setOverallChange(percentChange(previous.overall(), current.overall()));
    kpi.setFaithfulnessChange(percentChange(previous.faithfulness(), current.faithfulness()));
    kpi.setContextPrecisionChange(percentChange(previous.contextPrecision(), current.contextPrecision()));
    kpi.setAnswerRelevanceChange(percentChange(previous.answerRelevance(), current.answerRelevance()));

    SampleStatsVo sampleStats = new SampleStatsVo();
    sampleStats.setTotalSamples(current.sampleCount());
    sampleStats.setFailedSamples(current.failedCount());
    sampleStats.setFailedRate(current.failedRate());

    OverviewVo vo = new OverviewVo();
    vo.setKpis(kpi);
    vo.setSamples(sampleStats);
    vo.setTrend(toTrendPointList(rows));
    vo.setAnomaly(AnomalyVo.detect(current.overall(), previous.overall(), current.failedRate()));

    return vo;
  }

  @Override
  public List<KbSliceVo> byKb(int days, Set<Long> readableKbIds) {
    int safeDays = normalizeDays(days);
    Set<Long> filtered = normalizeKbIds(readableKbIds);
    if (filtered.isEmpty()) {
      return List.of();
    }

    LocalDate today = LocalDate.now();
    LocalDate queryStart = today.minusDays(safeDays * 2 - 1);
    List<Long> orderedIds = new ArrayList<>(filtered);

    String inSql = orderedIds.stream().map(i -> "?").collect(Collectors.joining(","));
    String sql =
        String.format(
            """
            SELECT date, kb_id, sample_count, failed_count,
                   faithfulness_p50, context_precision_p50, answer_relevance_p50,
                   overall_p50, overall_p95, overall_mean, overall_std,
                   total_cost_cny
            FROM judge_metrics_daily
            WHERE kb_id IN (%s)
              AND date >= ?
            ORDER BY kb_id ASC, date ASC
            """,
            inSql);

    List<Object> args = new ArrayList<>();
    args.addAll(orderedIds);
    args.add(queryStart);
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());

    Map<Long, List<Map<String, Object>>> rowsByKb =
        rows.stream()
            .collect(
                Collectors.groupingBy(
                    row -> toLong(row.get("kb_id")),
                    LinkedHashMap::new,
                    Collectors.toList()));

        Map<Long, String> kbNames =
        knowledgeBaseMapper.selectList(new QueryWrapper<KnowledgeBase>().in("id", orderedIds))
            .stream()
            .collect(Collectors.toMap(kb -> kb.getId(), KnowledgeBase::getName, (a, b) -> a, LinkedHashMap::new));

    return rowsByKb.entrySet().stream()
        .map(
            entry -> {
              Long kb = entry.getKey();
              List<Map<String, Object>> kbRows = entry.getValue();
              TrendWindow current = aggregateWindow(kbRows, today.minusDays(safeDays - 1), today);
              TrendWindow previous =
                  aggregateWindow(kbRows, today.minusDays(safeDays * 2 - 1), today.minusDays(safeDays));

              KbSliceVo vo = new KbSliceVo();
              vo.setKbId(kb);
              vo.setKbName(kbNames.getOrDefault(kb, "" + kb));
              vo.setOverallScore(current.overall());
              vo.setTrend(percentChange(previous.overall(), current.overall()));
              vo.setSampleCount(current.sampleCount());
              return vo;
            })
        .sorted(
            Comparator.comparingInt((KbSliceVo vo) -> vo.getSampleCount() == null ? 0 : vo.getSampleCount())
                .reversed()
                .thenComparing(vo -> vo.getKbId() == null ? Long.MAX_VALUE : vo.getKbId()))
        .toList();
  }

  @Override
  public List<WorstCaseVo> worstCases(int limit, int days, Long kbId, Set<Long> readableKbIds) {
    int safeLimit = normalizeLimit(limit);
    int safeDays = normalizeDays(days);
    LocalDate start = LocalDate.now().minusDays(safeDays - 1);

    List<Map<String, Object>> rows;
    if (kbId == null) {
      List<Long> safeReadableKbIds =
          readableKbIds == null ? List.of() : readableKbIds.stream().filter(Objects::nonNull).toList();
      if (CollectionUtils.isEmpty(safeReadableKbIds)) {
        return List.of();
      }
      String sql =
          """
          SELECT id, answer_log_id, query, overall_score, created_at, judge_raw_response
          FROM judge_results
          WHERE status='COMPLETED'
            AND created_at >= :start
            AND EXISTS (
              SELECT 1
              FROM unnest(kb_ids) AS readable(kb_id)
              WHERE readable.kb_id IN (:readableKbIds)
            )
          ORDER BY overall_score ASC NULLS LAST, created_at DESC
          LIMIT :limit
          """;
      MapSqlParameterSource params =
          new MapSqlParameterSource()
              .addValue("start", start)
              .addValue("readableKbIds", safeReadableKbIds)
              .addValue("limit", safeLimit);
      rows = new NamedParameterJdbcTemplate(jdbcTemplate).queryForList(sql, params);
    } else {
      String sql =
          """
          SELECT id, answer_log_id, query, overall_score, created_at, judge_raw_response
          FROM judge_results
          WHERE status='COMPLETED'
            AND created_at >= ?
            AND ? = ANY(kb_ids)
          ORDER BY overall_score ASC NULLS LAST, created_at DESC
          LIMIT ?
          """;
      rows = jdbcTemplate.queryForList(sql, start, kbId, safeLimit);
    }

    return rows.stream().map(this::toWorstCase).toList();
  }

  @Override
  public CaseDetailVo caseDetail(Long judgeResultId) {
    if (judgeResultId == null) {
      throw new BizException(400, "judgeResultId cannot be null");
    }

    JudgeResult result = judgeResultMapper.selectById(judgeResultId);
    if (result == null) {
      throw new BizException(404, "JUDGE_RESULT_NOT_FOUND");
    }

    AnswerLog log = answerLogMapper.selectByIdWithKbIdsCsv(result.getAnswerLogId());
    List<Citation> citations = parseCitations(log == null ? null : log.getCitationsSnapshot());
    JsonNode raw = parseRawJson(result.getJudgeRawResponse());

    CaseDetailVo vo = new CaseDetailVo();
    vo.setJudgeResultId(result.getId());
    vo.setQuery(chooseFirstNonBlank(result.getQuery(), log == null ? null : log.getQuery()));
    vo.setAnswer(log == null ? null : log.getAnswer());
    vo.setCreatedAt(log == null ? null : log.getCreatedAt());
    vo.setKbIds(toKbIdList(result.getKbIds()));
    vo.setChunks(resolveChunks(citations));
    vo.setScores(buildScoreMap(result));
    vo.setJudgeReasoning(truncate(chooseFirstNonBlank(result.getJudgeReasoning(), extractReasoning(raw)), 320));
    vo.setImprovements(extractImprovements(raw));
    vo.setBottleneck(extractBottleneck(raw));
    return vo;
  }

  @Override
  public CostSummaryVo cost(int days) {
    int safeDays = normalizeDays(days);
    LocalDate start = LocalDate.now().minusDays(safeDays - 1);

    String totalSql =
        """
        SELECT COALESCE(SUM(total_cost_cny), 0) AS total_cost_cny,
               COALESCE(SUM(sample_count), 0) AS total_samples,
               COALESCE(SUM(failed_count), 0) AS failed_samples
        FROM judge_metrics_daily
        WHERE kb_id IS NULL
          AND date >= ?
        """;

    Map<String, Object> totalRow = jdbcTemplate.queryForMap(totalSql, start);
    BigDecimal totalCost = toBigDecimal(totalRow.get("total_cost_cny"));
    int totalSamples = toInt(totalRow.get("total_samples"));
    int failedSamples = toInt(totalRow.get("failed_samples"));

    String sourceSql =
        """
        SELECT source, COALESCE(SUM(judge_cost_cny), 0) AS total_cost
        FROM judge_results
        WHERE created_at >= ?
        GROUP BY source
        """;

    Map<String, BigDecimal> bySource = new LinkedHashMap<>();
    bySource.put("PRODUCTION", BigDecimal.ZERO.setScale(DEFAULT_SCALE, HALF_UP));
    bySource.put("GOLDEN_SET", BigDecimal.ZERO.setScale(DEFAULT_SCALE, HALF_UP));
    bySource.put("MANUAL", BigDecimal.ZERO.setScale(DEFAULT_SCALE, HALF_UP));

    for (Map<String, Object> row : jdbcTemplate.queryForList(sourceSql, start)) {
      String source = Objects.toString(row.get("source"), "").trim().toUpperCase();
      if (source.isBlank()) {
        continue;
      }
      bySource.put(source, toBigDecimal(row.get("total_cost")));
    }

    BigDecimal dailyAverage = safeDays <= 0 ? BigDecimal.ZERO : totalCost.divide(BigDecimal.valueOf(safeDays), DEFAULT_SCALE, HALF_UP);
    BigDecimal monthlyProjected = dailyAverage.multiply(BigDecimal.valueOf(30L)).setScale(DEFAULT_SCALE, HALF_UP);

    CostSummaryVo vo = new CostSummaryVo();
    vo.setTotalCny(totalCost);
    vo.setDailyAverageCny(dailyAverage);
    vo.setMonthlyProjectedCny(monthlyProjected);
    vo.setTotalCalls(totalSamples);
    vo.setFailedCalls(failedSamples);
    vo.setCostBySource(bySource);
    return vo;
  }

  @Override
  public BigDecimal costThisMonth() {
    LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
    String sql =
        """
        SELECT COALESCE(SUM(judge_cost_cny), 0)
        FROM judge_results
        WHERE created_at >= ?
        """;
    return toBigDecimal(jdbcTemplate.queryForObject(sql, Object.class, monthStart.atStartOfDay()));
  }

  @Override
  public int goldenSetEnabledQuestionCount() {
    String sql = "SELECT COUNT(1) FROM eval_questions WHERE judge_enabled = TRUE";
    Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
    return count == null ? 0 : count;
  }

  private List<Map<String, Object>> queryGlobalMetricsRows(LocalDate start) {
    String sql =
        """
        SELECT date, sample_count, failed_count,
               faithfulness_p50, context_precision_p50, answer_relevance_p50,
               overall_p50, overall_p95, overall_mean, total_cost_cny
        FROM judge_metrics_daily
        WHERE kb_id IS NULL
          AND date >= ?
        ORDER BY date ASC
        """;
    return jdbcTemplate.queryForList(sql, start);
  }

  private List<Map<String, Object>> queryKbMetricsRows(Long kbId, LocalDate start) {
    String sql =
        """
        SELECT date, sample_count, failed_count,
               faithfulness_p50, context_precision_p50, answer_relevance_p50,
               overall_p50, overall_p95, overall_mean, total_cost_cny
        FROM judge_metrics_daily
        WHERE kb_id = ?
          AND date >= ?
        ORDER BY date ASC
        """;
    return jdbcTemplate.queryForList(sql, kbId, start);
  }

  private List<TrendPointVo> toTrendPointList(List<Map<String, Object>> rows) {
    return rows.stream()
        .sorted(
            Comparator.comparing(
                row -> toDate(row.get("date")),
                Comparator.nullsLast(Comparator.naturalOrder())))
        .map(this::toTrendPoint)
        .toList();
  }

  private TrendPointVo toTrendPoint(Map<String, Object> row) {
    TrendPointVo vo = new TrendPointVo();
    vo.setDate(toDate(row.get("date")));
    vo.setOverall(toBigDecimal(row.get("overall_p50")));
    vo.setFaithfulness(toBigDecimal(row.get("faithfulness_p50")));
    vo.setContextPrecision(toBigDecimal(row.get("context_precision_p50")));
    vo.setAnswerRelevance(toBigDecimal(row.get("answer_relevance_p50")));
    vo.setSampleCount(toInt(row.get("sample_count")));
    return vo;
  }

  private TrendWindow aggregateWindow(List<Map<String, Object>> rows, LocalDate from, LocalDate to) {
    if (rows == null || rows.isEmpty() || from == null || to == null || to.isBefore(from)) {
      return TrendWindow.empty();
    }

    BigDecimal overallSum = BigDecimal.ZERO;
    BigDecimal faithfulnessSum = BigDecimal.ZERO;
    BigDecimal contextPrecisionSum = BigDecimal.ZERO;
    BigDecimal answerRelevanceSum = BigDecimal.ZERO;
    BigDecimal costSum = BigDecimal.ZERO;
    BigDecimal failedWeight = BigDecimal.ZERO;

    int sampleTotal = 0;
    int failedTotal = 0;

    for (Map<String, Object> row : rows) {
      LocalDate date = toDate(row.get("date"));
      if (date == null || date.isBefore(from) || date.isAfter(to)) {
        continue;
      }

      int sample = toInt(row.get("sample_count"));
      int failed = toInt(row.get("failed_count"));
      BigDecimal sampleBig = BigDecimal.valueOf(Math.max(sample, 0));

      overallSum = overallSum.add(toBigDecimal(row.get("overall_p50")).multiply(sampleBig));
      faithfulnessSum = faithfulnessSum.add(toBigDecimal(row.get("faithfulness_p50")).multiply(sampleBig));
      contextPrecisionSum = contextPrecisionSum.add(toBigDecimal(row.get("context_precision_p50")).multiply(sampleBig));
      answerRelevanceSum = answerRelevanceSum.add(toBigDecimal(row.get("answer_relevance_p50")).multiply(sampleBig));
      costSum = costSum.add(toBigDecimal(row.get("total_cost_cny")));
      failedWeight = failedWeight.add(BigDecimal.valueOf(Math.max(failed, 0)));
      sampleTotal += Math.max(sample, 0);
      failedTotal += Math.max(failed, 0);
    }

    if (sampleTotal <= 0) {
      return TrendWindow.empty();
    }

    BigDecimal denominator = BigDecimal.valueOf(sampleTotal);
    BigDecimal overall = overallSum.divide(denominator, DEFAULT_SCALE, HALF_UP);
    BigDecimal faithfulness = faithfulnessSum.divide(denominator, DEFAULT_SCALE, HALF_UP);
    BigDecimal contextPrecision = contextPrecisionSum.divide(denominator, DEFAULT_SCALE, HALF_UP);
    BigDecimal answerRelevance = answerRelevanceSum.divide(denominator, DEFAULT_SCALE, HALF_UP);

    BigDecimal failedRate = failedWeight.divide(denominator, DEFAULT_SCALE, HALF_UP);
    return new TrendWindow(overall, faithfulness, contextPrecision, answerRelevance, costSum, sampleTotal, failedTotal, failedRate);
  }

  private List<Long> toKbIdList(Long[] kbIds) {
    if (kbIds == null || kbIds.length == 0) {
      return List.of();
    }
    return Arrays.stream(kbIds).filter(Objects::nonNull).toList();
  }

  private Map<String, BigDecimal> buildScoreMap(JudgeResult result) {
    Map<String, BigDecimal> scores = new LinkedHashMap<>();
    scores.put("faithfulness", defaultZero(result.getFaithfulness()));
    scores.put("contextPrecision", defaultZero(result.getContextPrecision()));
    scores.put("answerRelevance", defaultZero(result.getAnswerRelevance()));
    scores.put("overallScore", defaultZero(result.getOverallScore()));
    scores.put("overall", defaultZero(result.getOverallScore()));
    return scores;
  }

  private BigDecimal defaultZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO.setScale(DEFAULT_SCALE, HALF_UP) : value;
  }

  private WorstCaseVo toWorstCase(Map<String, Object> row) {
    WorstCaseVo vo = new WorstCaseVo();
    vo.setJudgeResultId(toLong(row.get("id")));
    vo.setAnswerLogId(toLong(row.get("answer_log_id")));
    vo.setQuery(Objects.toString(row.get("query"), null));
    vo.setOverallScore(toBigDecimal(row.get("overall_score")));
    vo.setCreatedAt(toDateTime(row.get("created_at")));
    vo.setTopIssue(extractTopIssue(row));
    return vo;
  }

  private String extractTopIssue(Map<String, Object> row) {
    return extractTopIssue(row == null ? null : Objects.toString(row.get("judge_raw_response"), null));
  }

  private String extractTopIssue(String raw) {
    JsonNode rawNode = parseRawJson(raw);
    if (rawNode == null || rawNode.isMissingNode()) {
      return null;
    }
    return firstNonBlank(
        extractFromDimension(rawNode, "COMPOSITE", "composite"),
        extractFromDimension(rawNode, "FAITHFULNESS", "faithfulness"),
        extractFromDimension(rawNode, "CONTEXT_PRECISION", "context_precision"),
        extractFromDimension(rawNode, "ANSWER_RELEVANCE", "answer_relevance"));
  }

  private String extractFromDimension(JsonNode raw, String... keys) {
    for (String key : keys) {
      JsonNode node = raw.get(key);
      if (node != null && node.isObject()) {
        JsonNode issues = node.path("issues");
        if (issues.isArray() && issues.size() > 0) {
          String candidate = text(issues.get(0));
          if (StringUtils.hasText(candidate)) {
            return candidate;
          }
        }
        String reasoning = text(node.path("reasoning"));
        if (StringUtils.hasText(reasoning)) {
          return reasoning;
        }
      }
    }
    return null;
  }

  private String chooseFirstNonBlank(String... values) {
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value;
      }
    }
    return null;
  }

  private List<ChunkSnapshotVo> resolveChunks(List<Citation> citations) {
    if (CollectionUtils.isEmpty(citations)) {
      return List.of();
    }

    List<Long> chunkIds =
        citations.stream()
            .map(Citation::getChunkId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (chunkIds.isEmpty()) {
      return List.of();
    }

    List<DocumentChunk> chunks = documentChunkMapper.selectBatchIds(chunkIds);
    Map<Long, String> chunkContentById =
        chunks.stream().collect(Collectors.toMap(DocumentChunk::getId, DocumentChunk::getContent, (a, b) -> a));

    return citations.stream()
        .filter(Objects::nonNull)
        .filter(c -> c.getChunkId() != null)
        .map(
            citation -> {
              ChunkSnapshotVo vo = new ChunkSnapshotVo();
              vo.setChunkId(citation.getChunkId());
              vo.setDocId(citation.getDocId());
              vo.setSnippet(firstText(citation.getTextSnippet(), ""));
              vo.setContent(chunkContentById.getOrDefault(citation.getChunkId(), ""));
              vo.setScore(citation.getScore());
              vo.setRelevant(citation.getRelevant());
              return vo;
            })
        .toList();
  }

  private List<Citation> parseCitations(String snapshot) {
    if (!StringUtils.hasText(snapshot)) {
      return List.of();
    }
    try {
      return objectMapper.readValue(snapshot, CITATION_LIST_TYPE);
    } catch (JsonProcessingException e) {
      return List.of();
    }
  }

  private JsonNode parseRawJson(String raw) {
    if (!StringUtils.hasText(raw)) {
      return null;
    }
    try {
      return objectMapper.readTree(raw);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private String extractReasoning(JsonNode raw) {
    if (raw == null || !raw.isObject()) {
      return null;
    }

    JsonNode composite = pickNode(raw, "COMPOSITE", "composite");
    if (composite != null && composite.isObject()) {
      String reasoning = text(composite.path("reasoning"));
      if (StringUtils.hasText(reasoning)) {
        return reasoning;
      }
    }

    for (String key : List.of("FAITHFULNESS", "CONTEXT_PRECISION", "ANSWER_RELEVANCE")) {
      JsonNode node = raw.get(key);
      if (node != null && node.isObject()) {
        String reasoning = text(node.path("reasoning"));
        if (StringUtils.hasText(reasoning)) {
          return reasoning;
        }
      }
    }
    return null;
  }

  private String extractBottleneck(JsonNode raw) {
    if (raw == null) {
      return null;
    }
    JsonNode composite = pickNode(raw, "COMPOSITE", "composite");
    if (composite != null && composite.isObject()) {
      String bottleneck = text(composite.path("bottleneck"));
      if (StringUtils.hasText(bottleneck)) {
        return bottleneck;
      }
      JsonNode issues = composite.path("issues");
      if (issues.isArray()) {
        for (JsonNode issue : issues) {
          String item = text(issue);
          if (item.toLowerCase().startsWith("bottleneck=")) {
            return item.substring("bottleneck=".length()).trim();
          }
        }
      }
    }
    return null;
  }

  private List<String> extractImprovements(JsonNode raw) {
    if (raw == null) {
      return List.of();
    }

    JsonNode composite = pickNode(raw, "COMPOSITE", "composite");
    if (composite == null || !composite.isObject()) {
      return List.of();
    }

    List<String> improvements =
        extractTextList(composite.path("improvements"));
    if (improvements.isEmpty()) {
      improvements = extractTextList(composite.path("issues"));
    }
    return improvements.stream()
        .filter(StringUtils::hasText)
        .map(String::trim)
        .distinct()
        .limit(3)
        .toList();
  }

  private List<String> extractTextList(JsonNode arrayNode) {
    if (arrayNode == null || !arrayNode.isArray()) {
      return List.of();
    }
    return arrayNode.findValuesAsText("*");
  }

  private JsonNode pickNode(JsonNode raw, String... keys) {
    for (String key : keys) {
      JsonNode node = raw.get(key);
      if (node != null) {
        return node;
      }
    }
    return null;
  }

  private BigDecimal percentChange(BigDecimal previous, BigDecimal current) {
    if (previous == null || previous.signum() == 0 || current == null) {
      return BigDecimal.ZERO.setScale(DEFAULT_SCALE, HALF_UP);
    }
    return current.subtract(previous).divide(previous.abs(), DEFAULT_SCALE, HALF_UP);
  }

  private String truncate(String value, int maxLength) {
    if (!StringUtils.hasText(value)) {
      return value;
    }
    if (value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }

  private int normalizeLimit(int limit) {
    if (limit <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(limit, 100);
  }

  private int normalizeDays(int days) {
    if (days <= 0) {
      return DEFAULT_DAYS;
    }
    return days;
  }

  private Set<Long> normalizeKbIds(Set<Long> kbIds) {
    if (kbIds == null || kbIds.isEmpty()) {
      return Set.of();
    }
    return kbIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private LocalDate toDate(Object value) {
    if (value instanceof LocalDate d) {
      return d;
    }
    if (value instanceof java.sql.Date d) {
      return d.toLocalDate();
    }
    return null;
  }

  private LocalDateTime toDateTime(Object value) {
    if (value instanceof LocalDateTime dateTime) {
      return dateTime;
    }
    if (value instanceof java.sql.Timestamp timestamp) {
      return timestamp.toLocalDateTime();
    }
    return null;
  }

  private int toInt(Object value) {
    if (value == null) {
      return 0;
    }
    if (value instanceof Number n) {
      return n.intValue();
    }
    try {
      return Integer.parseInt(value.toString());
    } catch (Exception e) {
      return 0;
    }
  }

  private Long toLong(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number n) {
      return n.longValue();
    }
    try {
      return Long.valueOf(value.toString());
    } catch (Exception e) {
      return null;
    }
  }

  private BigDecimal toBigDecimal(Object value) {
    if (value == null) {
      return BigDecimal.ZERO.setScale(DEFAULT_SCALE, HALF_UP);
    }
    if (value instanceof BigDecimal b) {
      return b.setScale(DEFAULT_SCALE, HALF_UP);
    }
    if (value instanceof Number n) {
      return BigDecimal.valueOf(n.doubleValue()).setScale(DEFAULT_SCALE, HALF_UP);
    }
    try {
      return new BigDecimal(value.toString()).setScale(DEFAULT_SCALE, HALF_UP);
    } catch (Exception e) {
      return BigDecimal.ZERO.setScale(DEFAULT_SCALE, HALF_UP);
    }
  }

  private String firstText(String value, String fallback) {
    return StringUtils.hasText(value) ? value : fallback;
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value;
      }
    }
    return null;
  }

  private String text(JsonNode node) {
    return node == null || node.isNull() ? "" : node.asText("").trim();
  }

  private record TrendWindow(
      BigDecimal overall,
      BigDecimal faithfulness,
      BigDecimal contextPrecision,
      BigDecimal answerRelevance,
      BigDecimal totalCost,
      int sampleCount,
      int failedCount,
      BigDecimal failedRate) {

    private static TrendWindow empty() {
      return new TrendWindow(
          BigDecimal.ZERO.setScale(DEFAULT_SCALE, HALF_UP),
          BigDecimal.ZERO.setScale(DEFAULT_SCALE, HALF_UP),
          BigDecimal.ZERO.setScale(DEFAULT_SCALE, HALF_UP),
          BigDecimal.ZERO.setScale(DEFAULT_SCALE, HALF_UP),
          BigDecimal.ZERO.setScale(DEFAULT_SCALE, HALF_UP),
          0,
          0,
          BigDecimal.ZERO.setScale(DEFAULT_SCALE, HALF_UP));
    }
  }
}
