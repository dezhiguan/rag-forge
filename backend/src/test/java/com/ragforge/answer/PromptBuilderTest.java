package com.ragforge.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.search.SearchResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromptBuilderTest {

  @Test
  void imageChunkHasOcrContextMarker() {
    SearchResult image = new SearchResult();
    image.setChunkModality("IMAGE");
    image.setContent("架构图 OCR 内容");

    String prompt =
        new PromptBuilder().build("架构是什么？", List.of(image), new KnowledgeBase());

    assertThat(prompt).contains("（以下内容来自图片 OCR + 上下文）");
  }
}
