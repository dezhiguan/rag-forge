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
    assertThat(registry.find("ragforge.answer.citation_rate").gauge().value()).isEqualTo(0.5);
    assertThat(registry.counter("ragforge.answer.guard_rail.blocked", "reason", "NO_CITATIONS").count())
        .isEqualTo(1.0);
    assertThat(registry.counter("ragforge.kb_access_denied", "operation", "filter_readable").count())
        .isEqualTo(1.0);
  }
}
