package com.ragforge.pipeline.chunker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextChunkerTest {

  private final TextChunker chunker = new TextChunker();

  @Test
  void chunk2000ChineseChars_respectsSizeAndOverlap() {
    String text = "测".repeat(2000);
    int chunkSize = 512;
    int chunkOverlap = 64;
    int step = chunkSize - chunkOverlap;

    var chunks = chunker.chunk(text, chunkSize, chunkOverlap);

    assertThat(chunks).hasSize(5);
    assertThat(chunks.get(0).getContent()).hasSize(chunkSize);
    assertThat(chunks.get(4).getContent()).hasSize(2000 - 4 * step);

    for (int i = 0; i < chunks.size(); i++) {
      assertThat(chunks.get(i).getIndex()).isEqualTo(i);
      assertThat(chunks.get(i).getTokenCount()).isEqualTo(chunks.get(i).getContent().length());
    }

    for (int i = 1; i < chunks.size(); i++) {
      String prev = chunks.get(i - 1).getContent();
      String curr = chunks.get(i).getContent();
      String overlap = prev.substring(prev.length() - chunkOverlap);
      assertThat(curr).startsWith(overlap);
    }
  }

  @Test
  void chunkShortText_returnsSingleChunk() {
    String text = "短文本";
    var chunks = chunker.chunk(text, 512, 64);
    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).getContent()).isEqualTo(text);
    assertThat(chunks.get(0).getIndex()).isZero();
  }

  @Test
  void chunkEmptyText_returnsEmptyList() {
    assertThat(chunker.chunk("", 512, 64)).isEmpty();
    assertThat(chunker.chunk(null, 512, 64)).isEmpty();
  }
}
