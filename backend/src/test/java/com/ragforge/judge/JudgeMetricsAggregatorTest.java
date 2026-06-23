package com.ragforge.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.Socket;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    classes = JudgeMetricsAggregatorTest.AggregatorPgTestConfig.class,
    properties = {
      "spring.profiles.active=test",
      "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration",
      "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/ragforge",
      "spring.datasource.username=amy",
      "spring.datasource.password=amy",
      "spring.datasource.driver-class-name=org.postgresql.Driver",
      "spring.flyway.enabled=true",
      "spring.flyway.locations=classpath:db/migration"})
@ActiveProfiles("test")
@Transactional
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RAGFORGE_JUDGE_INTEGRATION", matches = "true")
class JudgeMetricsAggregatorTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private JudgeMetricsAggregator aggregator;

  @Test
  void aggregateWrites100SamplesWithCorrectP50AndP95ForKbAndGlobal() {
    Assumptions.assumeTrue(canReachLocalPostgres(), "Local Postgres not reachable in this env");

    LocalDateTime now = LocalDateTime.now().withNano(0);
    Timestamp createdAt = Timestamp.valueOf(now);

    for (int i = 1; i <= 100; i++) {
      BigDecimal score = BigDecimal.valueOf(i).divide(BigDecimal.valueOf(100), 3, RoundingMode.HALF_UP);
      jdbcTemplate.update(
          "INSERT INTO judge_results (answer_log_id, kb_ids, query, status, source, overall_score, faithfulness, context_precision, answer_relevance, judge_cost_cny, created_at) "
              + "VALUES (NULL, '{1,2}', ?, 'COMPLETED', 'PRODUCTION', ?, ?, ?, ?, 0.0004, ?) ",
          "q-" + i,
          score,
          score,
          score,
          score,
          createdAt);
    }

    aggregator.aggregate();

    LocalDate today = now.toLocalDate();
    BigDecimal overallP50ByKb =
        jdbcTemplate.queryForObject(
            "SELECT overall_p50 FROM judge_metrics_daily WHERE date = ? AND kb_id = ? AND tenant_id = 'default'",
            BigDecimal.class,
            today,
            1L);
    BigDecimal overallP95ByKb =
        jdbcTemplate.queryForObject(
            "SELECT overall_p95 FROM judge_metrics_daily WHERE date = ? AND kb_id = ? AND tenant_id = 'default'",
            BigDecimal.class,
            today,
            1L);
    Integer sampleByKb =
        jdbcTemplate.queryForObject(
            "SELECT sample_count FROM judge_metrics_daily WHERE date = ? AND kb_id = ? AND tenant_id = 'default'",
            Integer.class,
            today,
            1L);

    BigDecimal overallP50Global =
        jdbcTemplate.queryForObject(
            "SELECT overall_p50 FROM judge_metrics_daily WHERE date = ? AND kb_id IS NULL AND tenant_id = 'default'",
            BigDecimal.class,
            today);
    BigDecimal overallP95Global =
        jdbcTemplate.queryForObject(
            "SELECT overall_p95 FROM judge_metrics_daily WHERE date = ? AND kb_id IS NULL AND tenant_id = 'default'",
            BigDecimal.class,
            today);
    Integer sampleGlobal =
        jdbcTemplate.queryForObject(
            "SELECT sample_count FROM judge_metrics_daily WHERE date = ? AND kb_id IS NULL AND tenant_id = 'default'",
            Integer.class,
            today);

    assertThat(overallP50ByKb).isEqualByComparingTo(new BigDecimal("0.505"));
    assertThat(overallP95ByKb).isEqualByComparingTo(new BigDecimal("0.951"));
    assertThat(sampleByKb).isEqualTo(100);

    assertThat(overallP50Global).isEqualByComparingTo(new BigDecimal("0.505"));
    assertThat(overallP95Global).isEqualByComparingTo(new BigDecimal("0.951"));
    assertThat(sampleGlobal).isEqualTo(100);
  }

  @Test
  void aggregateCanUpsertExistingDailyMetricsOnPostgres15() {
    Assumptions.assumeTrue(canReachLocalPostgres(), "Local Postgres not reachable in this env");

    LocalDateTime now = LocalDateTime.now().withNano(0);
    jdbcTemplate.update(
        "INSERT INTO judge_results (answer_log_id, kb_ids, query, status, source, overall_score, faithfulness, context_precision, answer_relevance, judge_cost_cny, created_at) "
            + "VALUES (NULL, '{5}', 'upsert-check', 'COMPLETED', 'PRODUCTION', 0.800, 0.800, 0.800, 0.800, 0.0004, ?) ",
        Timestamp.valueOf(now));

    assertThatCode(aggregator::aggregate).doesNotThrowAnyException();
    assertThatCode(aggregator::aggregate).doesNotThrowAnyException();
  }

  private static boolean canReachLocalPostgres() {
    try (Socket socket = new Socket("127.0.0.1", 5432)) {
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(JudgeMetricsAggregator.class)
  static class AggregatorPgTestConfig {}
}
