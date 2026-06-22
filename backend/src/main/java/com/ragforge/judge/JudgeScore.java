package com.ragforge.judge;

import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

@Data
public class JudgeScore {

  private final ScoreDimension dimension;
  private final BigDecimal score;
  private final String reasoning;
  private final List<String> issues;
  private final String rawResponse;
  private final Integer latencyMs;
  private final BigDecimal costCny;
  private final boolean success;
  private final String failureReason;
  private final boolean stable;

  public static JudgeScore success(
      ScoreDimension dimension,
      BigDecimal score,
      String reasoning,
      List<String> issues,
      String rawResponse,
      Integer latencyMs,
      BigDecimal costCny,
      boolean stable) {
    return new JudgeScore(
        dimension,
        score,
        reasoning,
        issues == null ? List.of() : List.copyOf(issues),
        rawResponse,
        latencyMs,
        costCny,
        true,
        null,
        stable);
  }

  public static JudgeScore failed(ScoreDimension dimension, String reason) {
    return new JudgeScore(
        dimension, null, null, List.of(), null, null, null, false, reason, false);
  }
}
