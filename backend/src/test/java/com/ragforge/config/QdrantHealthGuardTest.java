package com.ragforge.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QdrantHealthGuardTest {

  private QdrantClient client;
  private QdrantProperties props;

  @BeforeEach
  void setUp() {
    client = mock(QdrantClient.class);
    props = new QdrantProperties();
    props.setCollection("test");
    props.setTimeoutMs(1000);
  }

  @Test
  void passesWhenCollectionExists() {
    when(client.collectionExistsAsync(eq("test"), any(Duration.class)))
        .thenReturn(Futures.immediateFuture(Boolean.TRUE));
    QdrantHealthGuard guard = new QdrantHealthGuard(client, props);
    assertThatCode(guard::verifyQdrant).doesNotThrowAnyException();
  }

  @Test
  void throwsWhenCollectionMissing() {
    when(client.collectionExistsAsync(eq("test"), any(Duration.class)))
        .thenReturn(Futures.immediateFuture(Boolean.FALSE));
    QdrantHealthGuard guard = new QdrantHealthGuard(client, props);
    assertThatThrownBy(guard::verifyQdrant)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("collection 不存在");
  }

  @Test
  void throwsWhenConnectionFails() {
    when(client.collectionExistsAsync(eq("test"), any(Duration.class)))
        .thenReturn(Futures.immediateFailedFuture(new RuntimeException("unreachable")));
    QdrantHealthGuard guard = new QdrantHealthGuard(client, props);
    assertThatThrownBy(guard::verifyQdrant)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("无法连接 Qdrant");
  }
}
