package com.ragforge.judge;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ragforge.judge.aggregator-enabled", havingValue = "true", matchIfMissing = true)
public class JudgeMetricsAggregator {

  private final JdbcTemplate jdbcTemplate;

  private static final String UPSERT_SQL =
      """
          INSERT INTO judge_metrics_daily (
            date, kb_id, tenant_id,
            sample_count,
            failed_count,
            faithfulness_p50,
            faithfulness_p95,
            context_precision_p50,
            context_precision_p95,
            answer_relevance_p50,
            answer_relevance_p95,
            overall_p50,
            overall_p95,
            overall_mean,
            overall_std,
            total_cost_cny,
            updated_at
          )
          SELECT
            src.date,
            kb_id,
            tenant_id,
            sample_count,
            failed_count,
            faithfulness_p50,
            faithfulness_p95,
            context_precision_p50,
            context_precision_p95,
            answer_relevance_p50,
            answer_relevance_p95,
            overall_p50,
            overall_p95,
            overall_mean,
            overall_std,
            total_cost_cny,
            NOW()
          FROM (
            SELECT
              DATE(jr.created_at) AS d,
              k.kb_id AS kb_id,
              jr.tenant_id AS tenant_id,
              COUNT(*) AS sample_count,
              COUNT(*) FILTER (WHERE jr.status = 'FAILED') AS failed_count,
              PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY jr.faithfulness) FILTER (WHERE jr.status = 'COMPLETED') AS faithfulness_p50,
              PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY jr.faithfulness) FILTER (WHERE jr.status = 'COMPLETED') AS faithfulness_p95,
              PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY jr.context_precision) FILTER (WHERE jr.status = 'COMPLETED') AS context_precision_p50,
              PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY jr.context_precision) FILTER (WHERE jr.status = 'COMPLETED') AS context_precision_p95,
              PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY jr.answer_relevance) FILTER (WHERE jr.status = 'COMPLETED') AS answer_relevance_p50,
              PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY jr.answer_relevance) FILTER (WHERE jr.status = 'COMPLETED') AS answer_relevance_p95,
              PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY jr.overall_score) FILTER (WHERE jr.status = 'COMPLETED') AS overall_p50,
              PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY jr.overall_score) FILTER (WHERE jr.status = 'COMPLETED') AS overall_p95,
              AVG(jr.overall_score) FILTER (WHERE jr.status = 'COMPLETED') AS overall_mean,
              STDDEV_SAMP(jr.overall_score) FILTER (WHERE jr.status = 'COMPLETED') AS overall_std,
              COALESCE(SUM(jr.judge_cost_cny), 0) AS total_cost_cny
            FROM judge_results jr
            CROSS JOIN LATERAL UNNEST(jr.kb_ids) AS k(kb_id)
            WHERE jr.created_at >= NOW() - INTERVAL '7 days'
            GROUP BY DATE(jr.created_at), k.kb_id, jr.tenant_id

            UNION ALL

            SELECT
              DATE(jr.created_at) AS d,
              NULL AS kb_id,
              jr.tenant_id AS tenant_id,
              COUNT(*) AS sample_count,
              COUNT(*) FILTER (WHERE jr.status = 'FAILED') AS failed_count,
              PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY jr.faithfulness) FILTER (WHERE jr.status = 'COMPLETED') AS faithfulness_p50,
              PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY jr.faithfulness) FILTER (WHERE jr.status = 'COMPLETED') AS faithfulness_p95,
              PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY jr.context_precision) FILTER (WHERE jr.status = 'COMPLETED') AS context_precision_p50,
              PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY jr.context_precision) FILTER (WHERE jr.status = 'COMPLETED') AS context_precision_p95,
              PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY jr.answer_relevance) FILTER (WHERE jr.status = 'COMPLETED') AS answer_relevance_p50,
              PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY jr.answer_relevance) FILTER (WHERE jr.status = 'COMPLETED') AS answer_relevance_p95,
              PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY jr.overall_score) FILTER (WHERE jr.status = 'COMPLETED') AS overall_p50,
              PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY jr.overall_score) FILTER (WHERE jr.status = 'COMPLETED') AS overall_p95,
              AVG(jr.overall_score) FILTER (WHERE jr.status = 'COMPLETED') AS overall_mean,
              STDDEV_SAMP(jr.overall_score) FILTER (WHERE jr.status = 'COMPLETED') AS overall_std,
              COALESCE(SUM(jr.judge_cost_cny), 0) AS total_cost_cny
            FROM judge_results jr
            WHERE jr.created_at >= NOW() - INTERVAL '7 days'
            GROUP BY DATE(jr.created_at), jr.tenant_id
          ) src(
            date,
            kb_id,
            tenant_id,
            sample_count,
            failed_count,
            faithfulness_p50,
            faithfulness_p95,
            context_precision_p50,
            context_precision_p95,
            answer_relevance_p50,
            answer_relevance_p95,
            overall_p50,
            overall_p95,
            overall_mean,
            overall_std,
            total_cost_cny
          )
          ON CONFLICT (date, COALESCE(kb_id, -1), tenant_id)
          DO UPDATE SET
            sample_count = EXCLUDED.sample_count,
            failed_count = EXCLUDED.failed_count,
            faithfulness_p50 = EXCLUDED.faithfulness_p50,
            faithfulness_p95 = EXCLUDED.faithfulness_p95,
            context_precision_p50 = EXCLUDED.context_precision_p50,
            context_precision_p95 = EXCLUDED.context_precision_p95,
            answer_relevance_p50 = EXCLUDED.answer_relevance_p50,
            answer_relevance_p95 = EXCLUDED.answer_relevance_p95,
            overall_p50 = EXCLUDED.overall_p50,
            overall_p95 = EXCLUDED.overall_p95,
            overall_mean = EXCLUDED.overall_mean,
            overall_std = EXCLUDED.overall_std,
            total_cost_cny = EXCLUDED.total_cost_cny,
            updated_at = NOW();
          """;

  @Scheduled(cron = "0 */5 * * * *")
  @SchedulerLock(name = "judge-metrics-aggregator", lockAtMostFor = "PT4M")
  public void aggregate() {
    jdbcTemplate.execute(UPSERT_SQL);
    Timestamp now = Timestamp.valueOf(LocalDateTime.now());
    LocalDate start = now.toLocalDateTime().toLocalDate().minusDays(7);
    log.info("Judge metrics aggregated: updated_at={}, window_start={}", now, start);
  }
}
