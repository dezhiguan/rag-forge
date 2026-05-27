package com.ragforge.pipeline.embedder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.config.DashScopeProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class DashScopeEmbeddingServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void partitionBatches_splits30IntoThreeBatchesOf10() {
    List<String> texts = IntStream.range(0, 30).mapToObj(i -> "text-" + i).toList();

    List<List<String>> batches = DashScopeEmbeddingService.partitionBatches(texts, 10);

    assertThat(batches).hasSize(3);
    assertThat(batches.get(0)).hasSize(10);
    assertThat(batches.get(1)).hasSize(10);
    assertThat(batches.get(2)).hasSize(10);
  }

  @Test
  void parseEmbeddings_supportsDataArrayFormat() throws Exception {
    String json =
        """
        {
          "data": [
            {"embedding": [0.1, 0.2, 0.3]},
            {"embedding": [0.4, 0.5, 0.6]}
          ]
        }
        """;

    List<float[]> vectors =
        DashScopeEmbeddingService.parseEmbeddings(objectMapper.readTree(json), 2);

    assertThat(vectors).hasSize(2);
    assertThat(vectors.get(0)).containsExactly(0.1f, 0.2f, 0.3f);
    assertThat(vectors.get(1)).containsExactly(0.4f, 0.5f, 0.6f);
  }

  @Test
  void parseEmbeddings_supportsOutputEmbeddingsFormat() throws Exception {
    String json =
        """
        {
          "output": {
            "embeddings": [
              {"text_index": 1, "embedding": [0.4, 0.5]},
              {"text_index": 0, "embedding": [0.1, 0.2]}
            ]
          }
        }
        """;

    List<float[]> vectors =
        DashScopeEmbeddingService.parseEmbeddings(objectMapper.readTree(json), 2);

    assertThat(vectors.get(0)).containsExactly(0.1f, 0.2f);
    assertThat(vectors.get(1)).containsExactly(0.4f, 0.5f);
  }

  @Test
  void parseEmbeddings_throwsWhenResponseEmpty() {
    assertThatThrownBy(
            () -> DashScopeEmbeddingService.parseEmbeddings(objectMapper.createObjectNode(), 1))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("响应解析失败");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
  void embed_returns1024Dimensions() {
    DashScopeProperties properties = new DashScopeProperties();
    properties.setApiKey(System.getenv("DASHSCOPE_API_KEY"));
    DashScopeEmbeddingService service = new DashScopeEmbeddingService(properties, objectMapper);

    float[] vector = service.embed("后端开发");

    assertThat(vector).hasSize(1024);
    assertThat(vector).isNotEmpty();
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
  void embedBatch30Items_returns30Vectors() {
    DashScopeProperties properties = new DashScopeProperties();
    properties.setApiKey(System.getenv("DASHSCOPE_API_KEY"));
    properties.setBatchSize(10);
    DashScopeEmbeddingService service = new DashScopeEmbeddingService(properties, objectMapper);

    List<String> texts = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      texts.add("批量文本-" + i);
    }

    List<float[]> vectors = service.embedBatch(texts);

    assertThat(vectors).hasSize(30);
    assertThat(vectors.get(0)).hasSize(1024);
    assertThat(vectors.get(29)).hasSize(1024);
  }
}
