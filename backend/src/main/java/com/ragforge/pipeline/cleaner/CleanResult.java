package com.ragforge.pipeline.cleaner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class CleanResult {
  private String cleanedText = "";
  private List<RemovedRegion> removedRegions = new ArrayList<>();
  private Map<String, Integer> piiHits = new LinkedHashMap<>();
  private int llmTokensUsed = 0;

  public static CleanResult of(String cleanedText) {
    CleanResult result = new CleanResult();
    result.setCleanedText(cleanedText == null ? "" : cleanedText);
    return result;
  }
}
