package com.ragforge.pipeline.chunker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class StructuredHeadingChunkerStrategyTest {

  private final StructuredHeadingChunkerStrategy strategy = new StructuredHeadingChunkerStrategy();

  @Test
  void splitsChineseBracketSectionsSoFactsAreNotDiluted() {
    String text =
        "公司档案\n【基本信息】成立于2020年。\n【核心团队】公司创始人为张伟。\n【产品线】主打产品为星望助手。";
    List<Chunk> chunks = strategy.split(new CleanedText(text), new ChunkParams());

    assertThat(chunks.size()).isGreaterThanOrEqualTo(3);
    Chunk founder =
        chunks.stream()
            .filter(c -> c.getContent().contains("创始人为张伟"))
            .findFirst()
            .orElseThrow();
    // 关键:创始人所在块不应混入产品线信息(每个小节独立成块,避免主题稀释)。
    assertThat(founder.getContent()).doesNotContain("星望助手");
  }

  @Test
  void splitsChineseNumberedSections() {
    String text = "手册\n一、登录:输入账号密码。\n二、导入:上传本地文件。\n三、导出:生成报表并下载。";
    List<Chunk> chunks = strategy.split(new CleanedText(text), new ChunkParams());
    assertThat(chunks.size()).isGreaterThanOrEqualTo(3);
  }

  @Test
  void returnsEmptyForUnstructuredTextSoChainFallsBackToRecursive() {
    String text = "这是一段没有任何结构标记的普通说明文字,连续几句话,也没有编号或标题标记。";
    assertThat(strategy.split(new CleanedText(text), new ChunkParams())).isEmpty();
  }

  @Test
  void supportsTextButNotBinary() {
    assertThat(strategy.supports(meta("text/plain", "a.txt"))).isTrue();
    assertThat(strategy.supports(meta("application/pdf", "a.pdf"))).isFalse();
  }

  private DocumentMeta meta(String type, String name) {
    DocumentMeta m = new DocumentMeta();
    m.setContentType(type);
    m.setFilename(name);
    return m;
  }
}
