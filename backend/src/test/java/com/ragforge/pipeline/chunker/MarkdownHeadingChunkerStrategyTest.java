package com.ragforge.pipeline.chunker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownHeadingChunkerStrategyTest {

  private final MarkdownHeadingChunkerStrategy strategy = new MarkdownHeadingChunkerStrategy();

  @Test
  void splitKeepsNestedHeadingPath() {
    ChunkParams params = new ChunkParams();
    params.setChunkSize(120);
    params.setMaxHeadingLevel(3);

    List<Chunk> chunks =
        strategy.split(
            new CleanedText(
                """
                # 前言
                项目背景和目标。
                ## 第二章
                章节说明。
                ### 2.1 背景
                这里是关键背景内容。
                ## 第三章
                结论内容。
                """),
            params);

    assertThat(chunks).extracting(Chunk::getHeadingPath)
        .contains("前言", "前言/第二章", "前言/第二章/2.1 背景", "前言/第三章");
    assertThat(chunks).extracting(Chunk::getSeq).containsExactly(0, 1, 2, 3);
  }
}
