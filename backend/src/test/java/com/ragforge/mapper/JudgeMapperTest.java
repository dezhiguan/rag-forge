package com.ragforge.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ragforge.model.entity.JudgeMetricsDaily;
import com.ragforge.model.entity.JudgeResult;
import com.ragforge.model.entity.JudgeSamplingConfig;
import com.ragforge.support.BaseIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    classes = JudgeMapperTest.MapperTestConfig.class,
    properties = {
      "spring.profiles.active=test",
      "mybatis-plus.configuration.map-underscore-to-camel-case=true",
      "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration"
    })
@Transactional
class JudgeMapperTest extends BaseIntegrationTest {

  @Autowired private JudgeResultMapper judgeResultMapper;

  @Autowired private JudgeMetricsDailyMapper judgeMetricsDailyMapper;

  @Autowired private JudgeSamplingConfigMapper judgeSamplingConfigMapper;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void judgeResultInsertAndSelectByIdRoundTrip() {
    JudgeResult entity = new JudgeResult();
    entity.setKbIds(new Long[] {11L, 22L, 33L});
    entity.setQuery("How to verify llm judge table migration?");
    entity.setFaithfulness(new BigDecimal("0.915"));
    entity.setContextPrecision(new BigDecimal("0.902"));
    entity.setContextRecall(new BigDecimal("0.893"));
    entity.setAnswerRelevance(new BigDecimal("0.901"));
    entity.setCompleteness(new BigDecimal("0.881"));
    entity.setCitationAccuracy(new BigDecimal("0.872"));
    entity.setOverallScore(new BigDecimal("0.895"));
    entity.setJudgeModel("deepseek-chat");
    entity.setJudgePromptVersion("v1");
    entity.setJudgeReasoning("deterministic test sample");
    entity.setJudgeRawResponse("{\"score\":0.895}");
    entity.setJudgeLatencyMs(321);
    entity.setJudgeCostCny(new BigDecimal("0.0123"));
    entity.setStatus("COMPLETED");
    entity.setSource("PRODUCTION");

    int inserted = judgeResultMapper.insert(entity);
    assertThat(inserted).isEqualTo(1);

    JudgeResult loaded = judgeResultMapper.selectById(entity.getId());
    assertThat(loaded).isNotNull();
    assertThat(loaded.getQuery()).isEqualTo(entity.getQuery());
    assertThat(loaded.getKbIds()).containsExactly(11L, 22L, 33L);
    assertThat(loaded.getStatus()).isEqualTo("COMPLETED");
    assertThat(loaded.getSource()).isEqualTo("PRODUCTION");
    assertThat(loaded.getJudgeRawResponse()).isEqualTo("{\"score\":0.895}");
  }

  @Test
  void judgeMetricsDailyPrimaryKeyConflictOnDuplicateDateKbAndTenant() {
    JudgeMetricsDaily row = new JudgeMetricsDaily();
    row.setDate(LocalDate.of(2026, 6, 23));
    row.setTenantId("default");
    row.setKbId(null);
    row.setSampleCount(1);
    row.setFailedCount(0);
    row.setTotalCostCny(new BigDecimal("1.1000"));
    row.setUpdatedAt(LocalDateTime.now());
    assertThat(judgeMetricsDailyMapper.insert(row)).isEqualTo(1);

    JudgeMetricsDaily dup = new JudgeMetricsDaily();
    dup.setDate(LocalDate.of(2026, 6, 23));
    dup.setTenantId("default");
    dup.setKbId(null);
    dup.setSampleCount(2);
    dup.setFailedCount(1);
    dup.setTotalCostCny(new BigDecimal("2.0000"));
    dup.setUpdatedAt(LocalDateTime.now());

    assertThatThrownBy(() -> judgeMetricsDailyMapper.insert(dup))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void judgeSamplingConfigGlobalUniqueIndexFromMigrationAndInsert() {
    Long seedCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM judge_sampling_config WHERE scope_type='GLOBAL'", Long.class);
    assertThat(seedCount).isEqualTo(1L);

    JudgeSamplingConfig duplicate = new JudgeSamplingConfig();
    duplicate.setScopeType("GLOBAL");
    duplicate.setSampleRate(new BigDecimal("0.010"));
    duplicate.setUpdatedBy("unit-test");

    assertThatThrownBy(() -> judgeSamplingConfigMapper.insert(duplicate))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @MapperScan("com.ragforge.mapper")
  static class MapperTestConfig {}
}
