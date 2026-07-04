package com.ragforge.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import com.ragforge.common.BizException;
import com.ragforge.config.QdrantProperties;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.PointId;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.UpdateResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QdrantVectorStoreTest {

  private QdrantClient client;
  private QdrantVectorStore store;

  @BeforeEach
  void setUp() {
    client = mock(QdrantClient.class);
    QdrantProperties props = new QdrantProperties();
    props.setCollection("test");
    props.setVectorDim(4);
    props.setTimeoutMs(2000);
    store = new QdrantVectorStore(client, props);
  }

  private float[] vec() {
    return new float[] {0.1f, 0.2f, 0.3f, 0.4f};
  }

  @Test
  void upsert_buildsPointsAndCallsClient() {
    when(client.upsertAsync(eq("test"), anyList()))
        .thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));

    store.upsert(
        List.of(
            new QdrantVectorStore.ChunkPoint(1L, 10L, 100L, "TEXT", vec()),
            new QdrantVectorStore.ChunkPoint(2L, 10L, 100L, "IMAGE", vec())));

    @SuppressWarnings("unchecked")
    var captor = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(client).upsertAsync(eq("test"), captor.capture());
    List<PointStruct> points = captor.getValue();
    assertThat(points).hasSize(2);
    assertThat(points.get(0).getId().getNum()).isEqualTo(1L);
    assertThat(points.get(0).getPayloadMap().get("kb_id").getIntegerValue()).isEqualTo(10L);
  }

  @Test
  void upsert_emptyIsNoop() {
    store.upsert(List.of());
    verify(client, never()).upsertAsync(any(), anyList());
  }

  @Test
  void upsert_rejectsWrongDimension() {
    assertThatThrownBy(
            () ->
                store.upsert(
                    List.of(
                        new QdrantVectorStore.ChunkPoint(
                            1L, 10L, 100L, "TEXT", new float[] {0.1f}))))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("维度");
  }

  @Test
  void upsert_clientErrorThrowsFriendly() {
    when(client.upsertAsync(eq("test"), anyList()))
        .thenReturn(Futures.immediateFailedFuture(new RuntimeException("grpc down")));
    assertThatThrownBy(
            () ->
                store.upsert(
                    List.of(new QdrantVectorStore.ChunkPoint(1L, 10L, 100L, "TEXT", vec()))))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("向量库写入");
  }

  @Test
  void search_mapsScoredPointsToChunks() {
    ScoredPoint p1 = ScoredPoint.newBuilder().setId(PointIdFactory.id(11L)).setScore(0.9f).build();
    ScoredPoint p2 = ScoredPoint.newBuilder().setId(PointIdFactory.id(22L)).setScore(0.7f).build();
    when(client.searchAsync(any(SearchPoints.class)))
        .thenReturn(Futures.immediateFuture(List.of(p1, p2)));

    List<QdrantVectorStore.ScoredChunk> hits =
        store.search(vec(), List.of(10L), null, null, 5);

    assertThat(hits).hasSize(2);
    assertThat(hits.get(0).chunkId()).isEqualTo(11L);
    assertThat(hits.get(0).score()).isEqualTo(0.9, org.assertj.core.data.Offset.offset(1e-6));
    assertThat(hits.get(1).chunkId()).isEqualTo(22L);
  }

  @Test
  void search_rejectsWrongQueryDimension() {
    assertThatThrownBy(() -> store.search(new float[] {0.1f}, List.of(10L), null, null, 5))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("维度");
  }

  @Test
  void search_clientErrorThrowsFriendly() {
    when(client.searchAsync(any(SearchPoints.class)))
        .thenReturn(Futures.immediateFailedFuture(new RuntimeException("timeout")));
    assertThatThrownBy(() -> store.search(vec(), List.of(10L), null, null, 5))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("向量检索服务暂时不可用");
  }

  @Test
  void deleteByDocId_callsClientWithFilter() {
    when(client.deleteAsync(eq("test"), any(Filter.class)))
        .thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));
    store.deleteByDocId(100L);
    verify(client).deleteAsync(eq("test"), any(Filter.class));
  }

  @Test
  void deleteByDocId_swallowsErrorBestEffort() {
    when(client.deleteAsync(eq("test"), any(Filter.class)))
        .thenReturn(Futures.immediateFailedFuture(new RuntimeException("boom")));
    // best-effort：不抛异常
    assertThatCode(() -> store.deleteByDocId(100L)).doesNotThrowAnyException();
  }

  @Test
  void deleteByChunkIds_callsClientAndEmptyNoop() {
    when(client.deleteAsync(eq("test"), anyList()))
        .thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));
    store.deleteByChunkIds(List.of(1L, 2L));
    @SuppressWarnings("unchecked")
    var captor = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(client).deleteAsync(eq("test"), captor.capture());
    assertThat((List<PointId>) captor.getValue()).hasSize(2);

    store.deleteByChunkIds(List.of());
    // 空列表不再调用（仍是上次的 1 次）
    verify(client).deleteAsync(eq("test"), anyList());
  }
}
