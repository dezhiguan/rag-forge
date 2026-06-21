package com.ragforge.pipeline.chunker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ragforge.pipeline.embedder.EmbeddingService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SemanticChunkerStrategyTest {

  @Mock private EmbeddingService embeddingService;

  @Test
  void splitMergesSimilarAdjacentSentencesAndCutsDissimilarOnes() {
    when(embeddingService.embed("用户登录成功。")).thenReturn(new float[] {1.0f, 0.0f});
    when(embeddingService.embed("登录后进入首页。")).thenReturn(new float[] {0.9f, 0.1f});
    when(embeddingService.embed("发票号码需要脱敏。")).thenReturn(new float[] {0.0f, 1.0f});

    SemanticChunkerStrategy strategy = new SemanticChunkerStrategy(embeddingService);
    ChunkParams params = new ChunkParams();
    params.setChunkSize(100);
    params.setSimThreshold(0.65);

    List<Chunk> chunks =
        strategy.split(new CleanedText("用户登录成功。登录后进入首页。发票号码需要脱敏。"), params);

    assertThat(chunks).hasSize(2);
    assertThat(chunks.get(0).getContent()).contains("用户登录成功", "登录后进入首页");
    assertThat(chunks.get(1).getContent()).contains("发票号码需要脱敏");
  }
}
