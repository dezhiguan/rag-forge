package com.ragforge.pipeline.embedder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.config.EmbeddingProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashScopeVlEmbeddingClientTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void embed_textBatchSendsContentsAndReturnsVectors() throws Exception {
    try (MockEmbeddingServer server = MockEmbeddingServer.start(3)) {
      DashScopeVlEmbeddingClient client = new DashScopeVlEmbeddingClient(properties(server.url()), objectMapper);

      List<float[]> vectors =
          client.embed(
              List.of(
                  EmbeddingInput.text("alpha"),
                  EmbeddingInput.text("beta"),
                  EmbeddingInput.text("gamma")));

      assertThat(vectors).hasSize(3);
      assertThat(vectors).allSatisfy(vector -> assertThat(vector).hasSize(2560));
      assertThat(server.lastRequest()).contains("\"text\":\"alpha\"");
      assertThat(server.lastRequest()).contains("\"text\":\"beta\"");
      assertThat(server.lastRequest()).contains("\"text\":\"gamma\"");
    }
  }

  @Test
  void embed_imageSendsDataUriAndReturnsVector() throws Exception {
    try (MockEmbeddingServer server = MockEmbeddingServer.start(1)) {
      DashScopeVlEmbeddingClient client = new DashScopeVlEmbeddingClient(properties(server.url()), objectMapper);

      List<float[]> vectors =
          client.embed(List.of(EmbeddingInput.image(new byte[] {1, 2, 3}, "image/png")));

      assertThat(vectors).hasSize(1);
      assertThat(vectors.get(0)).hasSize(2560);
      assertThat(server.lastRequest()).contains("\"image\":\"data:image/png;base64,AQID\"");
    }
  }

  @Test
  void embed_mixedImageAndTextReturnsAlignedVectors() throws Exception {
    try (MockEmbeddingServer server = MockEmbeddingServer.start(2)) {
      DashScopeVlEmbeddingClient client = new DashScopeVlEmbeddingClient(properties(server.url()), objectMapper);

      List<float[]> vectors =
          client.embed(
              List.of(
                  EmbeddingInput.image(new byte[] {4, 5}, "image/jpeg"),
                  EmbeddingInput.text("query")));

      assertThat(vectors).hasSize(2);
      assertThat(vectors.get(0)).hasSize(2560);
      assertThat(vectors.get(1)).hasSize(2560);
      assertThat(server.lastRequest()).contains("\"image\":\"data:image/jpeg;base64,BAU=\"");
      assertThat(server.lastRequest()).contains("\"text\":\"query\"");
    }
  }

  @Test
  void parseEmbeddings_ordersByIndexAndValidatesDimension() throws Exception {
    String vector = vectorJson(2560);
    String json =
        """
        {"output":{"embeddings":[
          {"index":1,"type":"vl","embedding":%s},
          {"index":0,"type":"vl","embedding":%s}
        ]}}
        """
            .formatted(vector, vector);

    List<float[]> vectors =
        DashScopeVlEmbeddingClient.parseEmbeddings(objectMapper.readTree(json), 2);

    assertThat(vectors).hasSize(2);
    assertThat(vectors.get(0)).hasSize(2560);
    assertThat(vectors.get(1)).hasSize(2560);
  }

  @Test
  void parseEmbeddings_rejectsNonVlType() throws Exception {
    String json =
        """
        {"output":{"embeddings":[
          {"index":0,"type":"text","embedding":%s}
        ]}}
        """
            .formatted(vectorJson(2560));

    assertThatThrownBy(
            () -> DashScopeVlEmbeddingClient.parseEmbeddings(objectMapper.readTree(json), 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("type must be vl");
  }

  @Test
  void parseEmbeddings_rejectsWrongDimension() throws Exception {
    String json =
        """
        {"output":{"embeddings":[
          {"index":0,"type":"vl","embedding":[0.1,0.2]}
        ]}}
        """;

    assertThatThrownBy(
            () -> DashScopeVlEmbeddingClient.parseEmbeddings(objectMapper.readTree(json), 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dimension must be 2560");
  }

  private static String vectorJson(int size) {
    StringBuilder builder = new StringBuilder("[");
    for (int i = 0; i < size; i++) {
      if (i > 0) {
        builder.append(',');
      }
      builder.append("0.1");
    }
    return builder.append(']').toString();
  }

  private static EmbeddingProperties properties(String endpoint) {
    EmbeddingProperties properties = new EmbeddingProperties();
    properties.setApiKey("test-key");
    properties.getVl().setEndpoint(endpoint);
    properties.getVl().setBatchSize(10);
    return properties;
  }

  private static final class MockEmbeddingServer implements AutoCloseable {
    private final HttpServer server;
    private volatile String lastRequest;

    private MockEmbeddingServer(HttpServer server) {
      this.server = server;
    }

    static MockEmbeddingServer start(int count) throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
      MockEmbeddingServer wrapper = new MockEmbeddingServer(server);
      server.createContext(
          "/embed",
          exchange -> {
            wrapper.lastRequest =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] response =
                ("{\"output\":{\"embeddings\":" + embeddingsJson(count) + "}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
          });
      server.start();
      return wrapper;
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/embed";
    }

    String lastRequest() {
      return lastRequest;
    }

    @Override
    public void close() {
      server.stop(0);
    }

    private static String embeddingsJson(int count) {
      StringBuilder builder = new StringBuilder("[");
      String vector = vectorJson(2560);
      for (int i = 0; i < count; i++) {
        if (i > 0) {
          builder.append(',');
        }
        builder
            .append("{\"index\":")
            .append(i)
            .append(",\"type\":\"vl\",\"embedding\":")
            .append(vector)
            .append('}');
      }
      return builder.append(']').toString();
    }
  }
}
