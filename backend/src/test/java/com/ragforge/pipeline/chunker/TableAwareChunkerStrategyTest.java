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

  @Test
  void split_wholePolicyKeepsContiguousTableTogether() {
    ChunkParams params = new ChunkParams();
    params.setTablePolicy(TablePolicy.WHOLE);

    var chunks =
        strategy.split(
            new CleanedText(
                """
                intro
                | name | score |
                | amy | 99 |
                outro
                """),
            params);

    assertThat(chunks).hasSize(3);
    assertThat(chunks).extracting(Chunk::getContent)
        .containsExactly("intro", "| name | score |\n| amy | 99 |", "outro");
    assertThat(chunks.get(1).getChunkParamsJson())
        .containsEntry("tablePolicy", "WHOLE")
        .containsEntry("chunkSize", 500);
  }

  @Test
  void split_rowPolicyEmitsEachTableLineAsSeparateChunk() {
    ChunkParams params = new ChunkParams();
    params.setTablePolicy(TablePolicy.ROW);
    params.setChunkSize(128);

    var chunks =
        strategy.split(new CleanedText("| col | val |\n| a | 1 |"), params);

    assertThat(chunks).hasSize(2);
    assertThat(chunks).extracting(Chunk::getIndex).containsExactly(0, 1);
    assertThat(chunks).extracting(Chunk::getContent).containsExactly("| col | val |", "| a | 1 |");
    assertThat(chunks.get(0).getChunkParamsJson())
        .containsEntry("tablePolicy", "ROW")
        .containsEntry("chunkSize", 128);
  }

  @Test
  void split_detectsWhitespaceSeparatedTableRows() {
    var chunks = strategy.split(new CleanedText("A  B  C\n1  2  3"), null);

    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).getContent()).isEqualTo("A  B  C\n1  2  3");
  }

  @Test
  void split_nullOrBlankInputReturnsEmptyList() {
    assertThat(strategy.split(null, null)).isEmpty();
    assertThat(strategy.split(new CleanedText(null), null)).isEmpty();
    assertThat(strategy.split(new CleanedText("  \n  "), null)).isEmpty();
  }
}
