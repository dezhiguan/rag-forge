package com.ragforge.judge;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public interface JudgeScorer {

  JudgeScore score(JudgeContext context, ScoreDimension dimension);

  default JudgeScore scoreWithRetry(JudgeContext context, ScoreDimension dimension, int runs) {
    if (runs <= 1) {
      return score(context, dimension);
    }

    List<JudgeScore> all = new ArrayList<>();
    for (int i = 0; i < runs; i++) {
      all.add(score(context, dimension));
    }

    List<JudgeScore> success = all.stream().filter(JudgeScore::isSuccess).toList();
    if (success.isEmpty()) {
      JudgeScore last = all.get(all.size() - 1);
      return JudgeScore.failed(
          dimension,
          "scoreWithRetry failed: "
              + (last.getFailureReason() == null ? "unknown" : last.getFailureReason()));
    }

    if (success.size() == 1) {
      JudgeScore item = success.get(0);
      return JudgeScore.success(
          item.getDimension(),
          item.getScore(),
          item.getReasoning(),
          item.getIssues(),
          item.getRawResponse(),
          item.getLatencyMs(),
          item.getCostCny(),
          true);
    }

    List<JudgeScore> sorted = new java.util.ArrayList<>(success);
    sorted.sort(
        Comparator.comparing(score -> score.getScore() == null ? BigDecimal.ZERO : score.getScore()));
    BigDecimal median;
    int midIndex = sorted.size() / 2;
    if (success.size() % 2 == 1) {
      median = sorted.get(midIndex).getScore();
    } else {
      BigDecimal left = sorted.get(midIndex - 1).getScore();
      BigDecimal right = sorted.get(midIndex).getScore();
      median = left.add(right).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }
    BigDecimal firstScore = sorted.get(0).getScore();
    BigDecimal lastScore = sorted.get(sorted.size() - 1).getScore();
    boolean stable = firstScore.subtract(lastScore).abs().compareTo(new BigDecimal("0.2")) <= 0;

    BigDecimal costSum = BigDecimal.ZERO;
    int costCount = 0;
    for (JudgeScore score : success) {
      if (score.getCostCny() != null) {
        costSum = costSum.add(score.getCostCny());
        costCount++;
      }
    }

    JudgeScore sample = sorted.get(sorted.size() - 1);
    return JudgeScore.success(
        dimension,
        median,
        sample.getReasoning(),
        sample.getIssues(),
        joinRawResponses(success),
        sample.getLatencyMs(),
        costCount == 0 ? null : costSum.divide(BigDecimal.valueOf(costCount), 4, RoundingMode.HALF_UP),
        stable);
  }

  default String joinRawResponses(List<JudgeScore> scores) {
    return scores.stream()
        .map(JudgeScore::getRawResponse)
        .filter(s -> s != null)
        .reduce((a, b) -> a + "\n\n" + b)
        .orElse(null);
  }

  default JudgeScore scoreWithRetry(JudgeContext context, ScoreDimension dimension) {
    return scoreWithRetry(context, dimension, 2);
  }
}
