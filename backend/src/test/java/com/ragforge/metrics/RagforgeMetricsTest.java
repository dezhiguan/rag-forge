package com.ragforge.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class RagforgeMetricsTest {

  @Test
  void recordsAnswerAndKbAccessMetrics() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RagforgeMetrics metrics = new RagforgeMetrics(registry);

    metrics.recordAnswerTokens(100, 20);
    metrics.updateAnswerCitationRate(2, 4);
    metrics.recordAnswerGuardRailBlocked("NO_CITATIONS");
    metrics.recordKbAccessDenied("filter_readable");

    assertThat(registry.counter("ragforge.answer.tokens", "type", "prompt").count()).isEqualTo(100.0);
    assertThat(registry.counter("ragforge.answer.tokens", "type", "completion").count()).isEqualTo(20.0);
    assertThat(registry.counter("ragforge.answer.citations_total", "kb", "default").count())
        .isEqualTo(2.0);
    assertThat(registry.counter("ragforge.answer.retrieval_results_total", "kb", "default").count())
        .isEqualTo(4.0);
    assertThat(registry.counter("ragforge.answer.guard_rail.blocked", "reason", "NO_CITATIONS").count())
        .isEqualTo(1.0);
    assertThat(registry.counter("ragforge.kb_access_denied", "operation", "filter_readable").count())
        .isEqualTo(1.0);
  }

  @Test
  void recordsJudgeMetrics() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RagforgeMetrics metrics = new RagforgeMetrics(registry);

    metrics.recordJudgeRequests("PRODUCTION");
    metrics.recordJudgeDuration("PRODUCTION", 120_000_000L);
    metrics.recordJudgeFailed("PRODUCTION", "timeout");
    metrics.recordJudgeScore("FAITHFULNESS", 10L, java.math.BigDecimal.valueOf(0.8));
    metrics.recordJudgeCost("PRODUCTION", java.math.BigDecimal.valueOf(0.12));
    metrics.recordDeepSeekTokens(120, 50);

    assertThat(registry.counter("ragforge.judge.requests", "source", "PRODUCTION").count())
        .isEqualTo(1.0);
    assertThat(registry.timer("ragforge.judge.duration", "source", "PRODUCTION").count())
        .isEqualTo(1L);
    assertThat(registry.counter("ragforge.judge.failed", "source", "PRODUCTION", "reason", "timeout").count())
        .isEqualTo(1.0);
    assertThat(registry.counter("ragforge.judge.cost", "source", "PRODUCTION").count())
        .isEqualTo(0.12);
    assertThat(registry.counter("ragforge.deepseek.tokens", "type", "prompt").count())
        .isEqualTo(120.0);
    assertThat(registry.counter("ragforge.deepseek.tokens", "type", "completion").count())
        .isEqualTo(50.0);
  }
}
