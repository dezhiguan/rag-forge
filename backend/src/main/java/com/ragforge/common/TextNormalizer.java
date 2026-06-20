package com.ragforge.common;

import java.text.Normalizer;
import java.util.Locale;

public final class TextNormalizer {

  private TextNormalizer() {}

  public static String normalize(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
    return normalized.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
  }

  public static boolean normalizedContains(String content, String expectedSnippet) {
    String normalizedContent = normalize(content);
    String normalizedSnippet = normalize(expectedSnippet);
    return !normalizedSnippet.isEmpty() && normalizedContent.contains(normalizedSnippet);
  }

  public static String snippet(String text, int maxChars) {
    if (text == null) {
      return "";
    }
    String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).trim();
    if (normalized.length() <= maxChars) {
      return normalized;
    }
    return normalized.substring(0, maxChars);
  }
}
