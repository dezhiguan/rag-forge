package com.ragforge.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.Test;

class QdrantClientConfigTest {

  @Test
  void buildsClientWithApiKey() {
    QdrantProperties props = new QdrantProperties();
    props.setHost("127.0.0.1");
    props.setGrpcPort(6334);
    props.setApiKey("secret");
    QdrantClient client = new QdrantClientConfig().qdrantClient(props);
    assertThat(client).isNotNull();
    client.close();
  }

  @Test
  void buildsClientWithoutApiKey() {
    QdrantProperties props = new QdrantProperties();
    props.setHost("127.0.0.1");
    props.setGrpcPort(6334);
    props.setApiKey("");
    QdrantClient client = new QdrantClientConfig().qdrantClient(props);
    assertThat(client).isNotNull();
    client.close();
  }
}
