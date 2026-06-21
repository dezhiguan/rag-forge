package com.ragforge.pipeline.chunker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TableAwareChunkerStrategyTest {

  private final TableAwareChunkerStrategy strategy = new TableAwareChunkerStrategy();

  @Test
  void supportsReturnsFalseForPlainText() {
    DocumentMeta meta = new DocumentMeta();
    meta.setContentType("text/plain");

    assertThat(strategy.supports(meta)).isFalse();
  }

  @Test
  void supportsReturnsTrueForMarkdownHtmlAndPdf() {
    DocumentMeta markdown = new DocumentMeta();
    markdown.setContentType("text/markdown");
    DocumentMeta html = new DocumentMeta();
    html.setContentType("text/html; charset=utf-8");
    DocumentMeta pdf = new DocumentMeta();
    pdf.setContentType("application/pdf");

    assertThat(strategy.supports(markdown)).isTrue();
    assertThat(strategy.supports(html)).isTrue();
    assertThat(strategy.supports(pdf)).isTrue();
  }
}
