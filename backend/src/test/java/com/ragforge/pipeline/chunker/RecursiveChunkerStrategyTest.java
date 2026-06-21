package com.ragforge.pipeline.chunker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RecursiveChunkerStrategyTest {

  private final RecursiveChunkerStrategy strategy = new RecursiveChunkerStrategy();

  @Test
  void splitUsesSeparatorsByPriority() {
    ChunkParams params = new ChunkParams();
    params.setChunkSize(16);
    params.setOverlap(0);
    params.setSeparators(List.of("\n\n", "\n", "。", ","));

    List<Chunk> chunks =
        strategy.split(new CleanedText("第一段第一句。第一段第二句。\n\n第二段,a,b,c,d,e,f。"), params);

    assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);
    assertThat(chunks.get(0).getContent()).contains("第一段第一句");
    assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getContent().length()).isLessThanOrEqualTo(16));
    assertThat(chunks).extracting(Chunk::getSeq).containsExactlyElementsOf(
        java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
  }
}
