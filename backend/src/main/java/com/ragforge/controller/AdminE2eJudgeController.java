package com.ragforge.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.Result;
import com.ragforge.judge.JudgeMetricsAggregator;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/e2e")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminE2eJudgeController {

  private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final JudgeMetricsAggregator judgeMetricsAggregator;

  @GetMapping("/judge-result/_ping")
  public Result<Map<String, String>> ping() {
    return Result.ok(Map.of("status", "ok"));
  }

  @PostMapping("/judge-result")
  public Result<Map<String, Long>> seedJudgeResult(@RequestBody SeedJudgeResultRequest req) {
    if (req.getKbIds() == null || req.getKbIds().isEmpty()) {
      throw new IllegalArgumentException("kbIds required");
    }
    String tenantId = req.getTenantId() != null ? req.getTenantId() : "default";
    String query = req.getQuery() != null ? req.getQuery() : "e2e judge query";
    String answer = req.getAnswer() != null ? req.getAnswer() : "e2e answer with [1] citation";
    String citationsJson =
        req.getCitationsSnapshot() != null
            ? toJsonString(req.getCitationsSnapshot())
            : "[{\"chunkId\":1,\"score\":0.82,\"content\":\"snippet\",\"relevant\":true}]";
    LocalDateTime createdAt =
        req.getCreatedAt() != null ? LocalDateTime.parse(req.getCreatedAt(), ISO) : LocalDateTime.now();

    Long answerLogId =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO answer_logs (tenant_id, kb_ids, query, answer, citations_snapshot, created_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?)
            RETURNING id
            """,
            Long.class,
            tenantId,
            toSqlArray(req.getKbIds()),
            query,
            answer,
            citationsJson,
            Timestamp.valueOf(createdAt));

    String rawJson =
        req.getJudgeRawResponse() != null
            ? toJsonString(req.getJudgeRawResponse())
            : toJsonString(defaultRawResponse(req));

    Long judgeResultId =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO judge_results (
              answer_log_id, kb_ids, query,
              faithfulness, context_precision, answer_relevance, overall_score,
              judge_model, judge_prompt_version, judge_reasoning, judge_raw_response,
              judge_latency_ms, judge_cost_cny, status, source, tenant_id, created_at
            ) VALUES (
              ?, ?, ?,
              ?, ?, ?, ?,
              'deepseek-chat', 'v1', ?, ?::jsonb,
              ?, ?, ?, ?, ?, ?
            )
            RETURNING id
            """,
            Long.class,
            answerLogId,
            toSqlArray(req.getKbIds()),
            query,
            decimal(req.getFaithfulness(), "0.800"),
            decimal(req.getContextPrecision(), "0.700"),
            decimal(req.getAnswerRelevance(), "0.850"),
            decimal(req.getOverallScore(), "0.850"),
            req.getJudgeReasoning() != null ? req.getJudgeReasoning() : "e2e reasoning",
            rawJson,
            req.getJudgeLatencyMs() != null ? req.getJudgeLatencyMs() : 1200,
            decimal(req.getJudgeCostCny(), "0.0200"),
            req.getStatus() != null ? req.getStatus() : "COMPLETED",
            req.getSource() != null ? req.getSource() : "PRODUCTION",
            tenantId,
            Timestamp.valueOf(createdAt));

    return Result.ok(Map.of("judgeResultId", judgeResultId, "answerLogId", answerLogId));
  }

  @DeleteMapping("/judge-result")
  public Result<Map<String, Integer>> clearJudgeResults(
      @RequestParam(defaultValue = "default") String tenantId) {
    int metrics =
        jdbcTemplate.update("DELETE FROM judge_metrics_daily WHERE tenant_id = ?", tenantId);
    int results = jdbcTemplate.update("DELETE FROM judge_results WHERE tenant_id = ?", tenantId);
    jdbcTemplate.update(
        "DELETE FROM answer_logs WHERE tenant_id = ? AND (query LIKE 'e2e judge%' OR query LIKE 'q-acc%')",
        tenantId);
    return Result.ok(Map.of("judgeResults", results, "metricsRows", metrics));
  }

  @PostMapping("/judge-metrics/aggregate")
  public Result<Map<String, String>> triggerAggregator() {
    judgeMetricsAggregator.aggregate();
    return Result.ok(Map.of("status", "ok"));
  }

  @PostMapping("/kb-acl/grant")
  public Result<Void> grantKbAccess(@RequestBody KbAclGrantRequest req) {
    jdbcTemplate.update(
        """
        INSERT INTO kb_acl (kb_id, principal_type, principal_id, permission, created_at, updated_at)
        VALUES (?, 'user', ?, ?, NOW(), NOW())
        ON CONFLICT (kb_id, principal_type, principal_id)
        DO UPDATE SET permission = EXCLUDED.permission, updated_at = NOW()
        """,
        req.getKbId(),
        String.valueOf(req.getUserId()),
        req.getPermission() != null ? req.getPermission() : "read");
    return Result.ok();
  }

  @DeleteMapping("/kb-acl/revoke")
  public Result<Void> revokeKbAccess(
      @RequestParam Long userId, @RequestParam Long kbId) {
    jdbcTemplate.update(
        "DELETE FROM kb_acl WHERE kb_id = ? AND principal_type = 'user' AND principal_id = ?",
        kbId,
        String.valueOf(userId));
    return Result.ok();
  }

  private Array toSqlArray(List<Long> kbIds) {
    return jdbcTemplate.execute(
        (java.sql.Connection conn) -> conn.createArrayOf("bigint", kbIds.toArray(new Long[0])));
  }

  private BigDecimal decimal(Double value, String fallback) {
    if (value == null) {
      return new BigDecimal(fallback);
    }
    return BigDecimal.valueOf(value);
  }

  private BigDecimal decimal(String value, String fallback) {
    if (value == null) {
      return new BigDecimal(fallback);
    }
    return new BigDecimal(value);
  }

  private String toJsonString(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("invalid json payload", e);
    }
  }

  private Map<String, Object> defaultRawResponse(SeedJudgeResultRequest req) {
    return Map.of(
        "COMPOSITE",
        Map.of(
            "bottleneck",
            req.getBottleneck() != null ? req.getBottleneck() : "RETRIEVAL",
            "improvements",
            List.of("Improve retrieval recall", "Add more citations"),
            "faithfulness",
            Map.of("score", req.getFaithfulness() != null ? req.getFaithfulness() : 0.8, "reasoning", "ok")));
  }

  @Data
  public static class SeedJudgeResultRequest {
    private List<Long> kbIds;
    private String source;
    private String status;
    private Double faithfulness;
    private Double contextPrecision;
    private Double answerRelevance;
    private Double overallScore;
    private String createdAt;
    private Object judgeRawResponse;
    private String judgeReasoning;
    private String query;
    private String answer;
    private Object citationsSnapshot;
    private Integer judgeLatencyMs;
    private String judgeCostCny;
    private String tenantId;
    private String bottleneck;
  }

  @Data
  public static class KbAclGrantRequest {
    private Long userId;
    private Long kbId;
    private String permission;
  }
}
