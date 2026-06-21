package com.ragforge.pipeline.cleaner;

import java.util.regex.Pattern;

public final class PiiPatterns {

  private PiiPatterns() {}

  public static final Pattern PHONE =
      Pattern.compile("(?<!\\d)(1[3-9](?:[\\s\\-]*\\d){9})(?!\\d)");

  public static final Pattern ID_CARD =
      Pattern.compile("(?<!\\d)(\\d(?:[\\s\\-]*\\d){16}[\\s\\-]*[\\dXx])(?![\\dXx])");

  public static final Pattern EMAIL =
      Pattern.compile(
          "(?i)([a-z0-9._%+\\-])([a-z0-9._%+\\-]*)(@[a-z0-9][a-z0-9.\\-]*\\.[a-z]{2,})");

  public static final Pattern BANK_CARD =
      Pattern.compile("(?<!\\d)(\\d(?:[\\s\\-]*\\d){15,18})(?!\\d)");
}
