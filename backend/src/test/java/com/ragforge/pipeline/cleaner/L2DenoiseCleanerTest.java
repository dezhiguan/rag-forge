package com.ragforge.pipeline.cleaner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class L2DenoiseCleanerTest {

  private final L2DenoiseCleaner cleaner = new L2DenoiseCleaner();

  @Test
  void clean_removesRepeatedHeadersFootersTocAndWatermarks() {
    String text =
        """
        RAGForge Confidential
        Chapter 1
        content a
        page footer
\f
        RAGForge Confidential
        Chapter 2
        content b
        page footer
\f
        RAGForge Confidential
        目录
        第一章........3
        content c
        page footer
        """;

    CleanResult result = cleaner.clean(new RawText(text, "application/pdf", 3), new CleanProfile());

    assertThat(result.getCleanedText()).contains("content a", "content b", "content c");
    assertThat(result.getCleanedText()).doesNotContain("RAGForge Confidential", "page footer", "第一章........3");
    assertThat(result.getRemovedRegions()).hasSizeGreaterThanOrEqualTo(4);
    assertThat(result.getRemovedRegions()).extracting(RemovedRegion::getReason).contains("TOC", "WATERMARK");
  }

  @Test
  void clean_keepsNormalSinglePageContent() {
    CleanResult result = cleaner.clean(new RawText("Header\nreal content\nFooter", "text/plain", 1), new CleanProfile());
    assertThat(result.getCleanedText()).contains("Header", "real content", "Footer");
    assertThat(result.getRemovedRegions()).isEmpty();
  }

  @Test
  void clean_usesPageBoundariesWhenTextHasNoFormFeed() {
    String text =
        """
        Company Header
        first page content
        Footer 1
        Company Header
        second page content
        Footer 2
        """;
    List<String> pages =
        List.of(
            """
            Company Header
            first page content
            Footer 1
            """,
            """
            Company Header
            second page content
            Footer 2
            """);

    CleanResult result = cleaner.clean(new RawText(text, "application/pdf", 2, pages), new CleanProfile());

    assertThat(result.getCleanedText()).contains("first page content", "second page content");
    assertThat(result.getCleanedText()).doesNotContain("Company Header");
  }
}
