package com.ragforge.pipeline.cleaner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CleaningPipeline {

  private final List<Cleaner> cleaners;

  public CleanResult clean(RawText raw, CleanProfile profile) {
    CleanProfile effectiveProfile = profile == null ? new CleanProfile() : profile;
    String text = raw == null || raw.getText() == null ? "" : raw.getText();
    List<RemovedRegion> removedRegions = new ArrayList<>();
    Map<String, Integer> piiHits = new LinkedHashMap<>();
    int llmTokens = 0;

    for (Cleaner cleaner : orderedCleaners()) {
      if (!cleaner.enabled(effectiveProfile)) {
        continue;
      }
      CleanResult stage =
          cleaner.clean(
              new RawText(
                  text,
                  raw == null ? null : raw.getContentType(),
                  raw == null ? null : raw.getPageCount(),
                  raw == null ? List.of() : raw.getPageBoundaries()),
              effectiveProfile);
      text = stage.getCleanedText() == null ? "" : stage.getCleanedText();
      if (stage.getRemovedRegions() != null) {
        removedRegions.addAll(stage.getRemovedRegions());
      }
      if (stage.getPiiHits() != null) {
        stage.getPiiHits().forEach((key, count) -> piiHits.merge(key, count, Integer::sum));
      }
      llmTokens += stage.getLlmTokensUsed();
    }

    CleanResult result = CleanResult.of(text);
    result.setRemovedRegions(removedRegions);
    result.setPiiHits(piiHits);
    result.setLlmTokensUsed(llmTokens);
    return result;
  }

  private List<Cleaner> orderedCleaners() {
    return cleaners.stream().sorted(Comparator.comparingInt(this::order)).toList();
  }

  private int order(Cleaner cleaner) {
    return switch (cleaner.name()) {
      case "L1_NORMALIZE" -> 1;
      case "L2_DENOISE" -> 2;
      case "L3_PII_MASK" -> 3;
      default -> 99;
    };
  }
}
