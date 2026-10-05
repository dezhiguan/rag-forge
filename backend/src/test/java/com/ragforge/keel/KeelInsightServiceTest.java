package com.ragforge.keel;

import static org.assertj.core.api.Assertions.assertThat;

import com.ragforge.keel.KeelInsightService.Kb;
import com.ragforge.keel.KeelInsightService.Window;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KeelInsightServiceTest {

  @Test void emptyWindowKeepsLatencyNullAndMarksStaleBases() {
    var body =
        KeelInsightService.assemble(
            new Window(0, 0, 0, null, null),
            new Window(4, 4, 1, 100d, 200d),
            List.of(new Kb(7, "fault-manual", "电力运维组", 12, "2026-08-01T00:00:00Z", true)),
            Map.of(7L, new long[] {3, 1}),
            0.4);

    assertThat(body.get("searches24h")).isEqualTo(0L);
    assertThat(body.get("p50Ms")).isNull();
    assertThat(body.get("p95Ms")).isNull();
    assertThat(body.get("zeroResultRate")).isNull();
    assertThat(body.get("modelCostCny")).isEqualTo(0.4);
    @SuppressWarnings("unchecked")
    var rows = (List<Map<String, Object>>) body.get("knowledgeBases");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0))
        .containsEntry("kb", "fault-manual")
        .containsEntry("owner", "电力运维组")
        .containsEntry("documents", 12)
        .containsEntry("searches24h", 3L)
        .containsEntry("stale", true)
        .containsEntry("recallAt5", null);
    assertThat((Double) rows.get(0).get("zeroHitRate")).isEqualTo(1d / 3d);
  }

  @Test void percentilesStayWhenTheWindowHasSuccesses() {
    var body =
        KeelInsightService.assemble(
            new Window(10, 8, 2, 120d, 400d),
            new Window(0, 0, 0, null, null),
            List.of(),
            Map.of(),
            0d);

    assertThat(body.get("p50Ms")).isEqualTo(120d);
    assertThat(body.get("p95Ms")).isEqualTo(400d);
    assertThat(body.get("zeroResultRate")).isEqualTo(0.25d);
  }
}
