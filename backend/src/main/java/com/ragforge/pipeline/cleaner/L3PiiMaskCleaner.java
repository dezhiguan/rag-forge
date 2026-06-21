package com.ragforge.pipeline.cleaner;

import com.ragforge.common.BizException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class L3PiiMaskCleaner implements Cleaner {

  private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
  private static final char[] ID_CHECKS = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

  @Override
  public String name() {
    return "L3_PII_MASK";
  }

  @Override
  public boolean enabled(CleanProfile profile) {
    return profile != null && profile.isL3Enabled();
  }

  @Override
  public CleanResult clean(RawText raw, CleanProfile profile) {
    String text = raw == null || raw.getText() == null ? "" : raw.getText();
    Map<String, Integer> hits = new LinkedHashMap<>();
    text = replace(text, PiiPatterns.EMAIL, "email", hits, profile, this::maskEmail);
    text = replace(text, PiiPatterns.ID_CARD, "idCard", hits, profile, this::maskIdCardIfValid);
    text = replace(text, PiiPatterns.PHONE, "phone", hits, profile, this::maskPhone);
    text = replace(text, PiiPatterns.BANK_CARD, "bankCard", hits, profile, this::maskBankCard);
    CleanResult result = CleanResult.of(text);
    result.setPiiHits(hits);
    return result;
  }

  public String mask(String text) {
    CleanProfile profile = new CleanProfile();
    profile.setPiiPolicy(PiiPolicy.MASK);
    return clean(new RawText(text, "text/plain", 1), profile).getCleanedText();
  }

  private String replace(
      String text,
      Pattern pattern,
      String type,
      Map<String, Integer> hits,
      CleanProfile profile,
      Masker masker) {
    Matcher matcher = pattern.matcher(text);
    StringBuffer out = new StringBuffer();
    while (matcher.find()) {
      String raw = matcher.group();
      String masked = masker.mask(raw);
      if (masked == null) {
        matcher.appendReplacement(out, Matcher.quoteReplacement(raw));
        continue;
      }
      hits.merge(type, 1, Integer::sum);
      if (profile.getPiiPolicy() == PiiPolicy.REJECT) {
        throw new BizException(400, "PII_REJECTED: " + type);
      }
      if (profile.getPiiPolicy() == PiiPolicy.HASH) {
        masked = type + "#" + sha256(raw).substring(0, 16);
      }
      matcher.appendReplacement(out, Matcher.quoteReplacement(masked));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private String maskPhone(String raw) {
    String digits = digits(raw);
    if (!digits.matches("1[3-9]\\d{9}")) {
      return null;
    }
    return digits.substring(0, 3) + "****" + digits.substring(7);
  }

  private String maskIdCardIfValid(String raw) {
    String normalized = raw.replaceAll("[\\s-]", "").toUpperCase();
    if (!normalized.matches("\\d{17}[\\dX]") || !validIdCard(normalized)) {
      return null;
    }
    return normalized.substring(0, 6) + "********" + normalized.substring(14);
  }

  private String maskEmail(String raw) {
    int at = raw.indexOf('@');
    if (at <= 0 || at == raw.length() - 1) {
      return null;
    }
    return raw.charAt(0) + "***" + raw.substring(at);
  }

  private String maskBankCard(String raw) {
    String digits = digits(raw);
    if (raw != null && raw.replaceAll("[\\s-]", "").matches("\\d{17}[\\dXx]")) {
      return null;
    }
    if (!digits.matches("\\d{16,19}") || digits.matches("1[3-9]\\d{9}")) {
      return null;
    }
    return digits.substring(0, 4) + "********" + digits.substring(digits.length() - 4);
  }

  private static boolean validIdCard(String normalized) {
    int sum = 0;
    for (int i = 0; i < 17; i++) {
      sum += (normalized.charAt(i) - '0') * ID_WEIGHTS[i];
    }
    return ID_CHECKS[sum % 11] == normalized.charAt(17);
  }

  private static String digits(String raw) {
    return raw == null ? "" : raw.replaceAll("\\D", "");
  }

  private static String sha256(String raw) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return java.util.HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @FunctionalInterface
  private interface Masker {
    String mask(String raw);
  }
}
