package com.ragforge.pipeline.cleaner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class L2DenoiseCleaner implements Cleaner {

  @Override
  public String name() {
    return "L2_DENOISE";
  }

  @Override
  public boolean enabled(CleanProfile profile) {
    return profile != null && profile.isL2Enabled() && !profile.isSkipClean();
  }

  @Override
  public CleanResult clean(RawText raw, CleanProfile profile) {
    String text = raw == null || raw.getText() == null ? "" : raw.getText();
    List<String> pages = splitPages(raw, text);
    Set<String> repeatedEdgeLines = repeatedEdgeLines(pages);
    List<RemovedRegion> regions = new ArrayList<>();
    StringBuilder cleaned = new StringBuilder(text.length());
    int offset = 0;
    for (String line : text.split("\\n", -1)) {
      String normalized = normalizeLine(line);
      boolean remove =
          repeatedEdgeLines.contains(normalized)
              || isTocLine(line)
              || isWatermarkLine(normalized, repeatedEdgeLines);
      if (remove && !line.isBlank()) {
        regions.add(new RemovedRegion(offset, offset + line.length(), reason(line), line));
      } else {
        cleaned.append(line).append('\n');
      }
      offset += line.length() + 1;
    }
    CleanResult result = CleanResult.of(cleaned.toString().replaceAll("\\n{3,}", "\n\n").trim());
    result.setRemovedRegions(regions);
    return result;
  }

  private static List<String> splitPages(RawText raw, String text) {
    if (raw != null && raw.getPageBoundaries() != null && raw.getPageBoundaries().size() > 1) {
      return raw.getPageBoundaries();
    }
    String[] parts = text.split("\\f|\\n\\s*(?:第\\s*\\d+\\s*页|Page\\s+\\d+)\\s*\\n", -1);
    if (parts.length <= 1) {
      return List.of(text);
    }
    return List.of(parts);
  }

  private static Set<String> repeatedEdgeLines(List<String> pages) {
    Map<String, Integer> counts = new HashMap<>();
    for (String page : pages) {
      String[] lines = page.split("\\n");
      Set<String> seenInPage = new HashSet<>();
      for (int i = 0; i < lines.length; i++) {
        if (i >= 3 && i < Math.max(0, lines.length - 3)) {
          continue;
        }
        String normalized = normalizeLine(lines[i]);
        if (normalized.length() >= 4 && normalized.length() <= 80) {
          seenInPage.add(normalized);
        }
      }
      seenInPage.forEach(line -> counts.merge(line, 1, Integer::sum));
    }
    int threshold = Math.max(2, (int) Math.ceil(pages.size() * 0.5));
    Set<String> repeated = new HashSet<>();
    counts.forEach(
        (line, count) -> {
          if (count >= threshold) {
            repeated.add(line);
          }
        });
    return repeated;
  }

  private static boolean isTocLine(String line) {
    String trimmed = line == null ? "" : line.trim();
    return trimmed.matches(".{1,80}(\\.{3,}|…{2,})\\s*\\d{1,4}$")
        || trimmed.matches("^(目录|CONTENTS?)\\s*$");
  }

  private static boolean isWatermarkLine(String normalized, Set<String> repeatedEdgeLines) {
    return repeatedEdgeLines.contains(normalized)
        && normalized.length() <= 30
        && normalized.matches(".*(confidential|draft|机密|保密|水印).*");
  }

  private static String reason(String line) {
    if (isTocLine(line)) {
      return "TOC";
    }
    String normalized = normalizeLine(line);
    if (normalized.matches(".*(confidential|draft|机密|保密|水印).*")) {
      return "WATERMARK";
    }
    return "REPEATED_HEADER_FOOTER";
  }

  private static String normalizeLine(String line) {
    return (line == null ? "" : line).replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
  }
}
