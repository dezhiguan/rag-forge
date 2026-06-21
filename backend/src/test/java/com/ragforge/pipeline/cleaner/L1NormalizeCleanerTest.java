package com.ragforge.pipeline.cleaner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class L1NormalizeCleanerTest {

  private final L1NormalizeCleaner cleaner = new L1NormalizeCleaner();

  @Test
  void clean_normalizesUnicodeControlsAndWhitespace() {
    String[] cases = {
      "ＡＢＣ１２３", "Cafe\u0301", "a\u0000b", "a\r\nb", "a\r b", "a\t\tb",
      "a   b", "a \n b", "a\n\n\nb", "\uFEFFtext", "（测试）", "ａｂｃ＠ｅｘａｍｐｌｅ．ｃｏｍ",
      "①", "㍿", "  trim  ", "a\u0007b", "a\u001Fb", "line1\n \n \nline2",
      "１２３－４５６", "Ｔｅｓｔ", "中文　空格", "x\u000By", "x\fy", "x\r\n\r\ny",
      "％＋－", "｛｝［］", "“quote”", "NoChange", " tabs\t end ", "multi     space"
    };
    for (String input : cases) {
      CleanResult result = cleaner.clean(new RawText(input, "text/plain", 1), new CleanProfile());
      assertThat(result.getCleanedText()).doesNotContain("\u0000", "\u0007", "\u001F", "\r");
      assertThat(result.getLlmTokensUsed()).isZero();
    }
    assertThat(cleaner.clean(new RawText("ＡＢＣ１２３", null, 1), new CleanProfile()).getCleanedText())
        .isEqualTo("ABC123");
  }
}
