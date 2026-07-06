package com.ragforge.pipeline.indexer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.ragforge.config.ElasticsearchClientProvider;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import java.time.LocalDateTime;
import java.util.List;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EsIndexServiceTest {

  @Mock private ElasticsearchClient client;
  @Mock private ElasticsearchIndicesClient indicesClient;
  @Mock private ElasticsearchClientProvider clientProvider;

  private EsIndexService esIndexService;

  @BeforeEach
  void setUp() {
    when(clientProvider.get()).thenReturn(client);
    esIndexService = new EsIndexService(clientProvider);
    when(client.indices()).thenReturn(indicesClient);
  }

  @Test
  void init_createsIndexWhenMissing() throws Exception {
    BooleanResponse exists = mock(BooleanResponse.class);
    when(exists.value()).thenReturn(false);
    when(indicesClient.exists(any(ExistsRequest.class)))
        .thenReturn(exists);
    when(indicesClient.create(any(co.elastic.clients.elasticsearch.indices.CreateIndexRequest.class)))
        .thenReturn(mock(CreateIndexResponse.class));

    esIndexService.init();

    verify(indicesClient).create(any(co.elastic.clients.elasticsearch.indices.CreateIndexRequest.class));
  }

  @Test
  void init_skipsWhenIndexExists() throws Exception {
    BooleanResponse exists = mock(BooleanResponse.class);
    when(exists.value()).thenReturn(true);
    when(indicesClient.exists(any(ExistsRequest.class)))
        .thenReturn(exists);

    esIndexService.init();

    verify(indicesClient, never()).create(any(co.elastic.clients.elasticsearch.indices.CreateIndexRequest.class));
  }

  @Test
  void init_fallsBackToStandardAnalyzerWhenIkAnalyzerMissing() throws Exception {
    BooleanResponse exists = mock(BooleanResponse.class);
    when(exists.value()).thenReturn(false);
    when(indicesClient.exists(any(ExistsRequest.class))).thenReturn(exists);
    when(indicesClient.create(any(co.elastic.clients.elasticsearch.indices.CreateIndexRequest.class)))
        .thenThrow(new RuntimeException("unknown analyzer [ik_max_word]"))
        .thenReturn(mock(CreateIndexResponse.class));

    esIndexService.init();

    verify(indicesClient, times(2))
        .create(any(co.elastic.clients.elasticsearch.indices.CreateIndexRequest.class));
  }

  @Test
  void init_swallowsIndexCreationFailure() throws Exception {
    BooleanResponse exists = mock(BooleanResponse.class);
    when(exists.value()).thenReturn(false);
    when(indicesClient.exists(any(ExistsRequest.class))).thenReturn(exists);
    when(indicesClient.create(any(co.elastic.clients.elasticsearch.indices.CreateIndexRequest.class)))
        .thenThrow(new RuntimeException("cluster unavailable"));

    esIndexService.init();

    verify(indicesClient).create(any(co.elastic.clients.elasticsearch.indices.CreateIndexRequest.class));
  }

  @Test
  void indexChunks_emptyInput_returnsTrue() {
    assertThat(esIndexService.indexChunks(List.of(), document(1L))).isTrue();
    assertThat(esIndexService.indexChunks(null, document(1L))).isTrue();
    assertThat(esIndexService.indexChunks(List.of(chunk(1L, 1L, 100L, "skip")), null)).isTrue();
  }

  @Test
  void indexChunks_bulkSuccess_returnsTrue() throws Exception {
    BulkResponse response = BulkResponse.of(b -> b.errors(false).items(List.of()).took(1L));
    when(client.bulk(any(co.elastic.clients.elasticsearch.core.BulkRequest.class))).thenReturn(response);

    DocumentChunk chunk = chunk(10L, 1L, 100L, "hello");
    assertThat(esIndexService.indexChunks(List.of(chunk), document(1L))).isTrue();
  }

  @Test
  void indexChunks_bulkErrors_returnsFalse() throws Exception {
    BulkResponse response = BulkResponse.of(b -> b.errors(true).items(List.of()).took(1L));
    when(client.bulk(any(co.elastic.clients.elasticsearch.core.BulkRequest.class))).thenReturn(response);

    DocumentChunk chunk = chunk(11L, 2L, 100L, "world");
    assertThat(esIndexService.indexChunks(List.of(chunk), document(2L))).isFalse();
  }

  @Test
  void indexChunks_skipsChunksWithoutId() throws Exception {
    DocumentChunk missingId = chunk(null, 1L, 100L, "skip");
    assertThat(esIndexService.indexChunks(List.of(missingId), document(1L))).isTrue();
    verify(client, never()).bulk(any(co.elastic.clients.elasticsearch.core.BulkRequest.class));
  }

  @Test
  void indexChunks_bulkClientFailure_returnsFalse() throws Exception {
    when(client.bulk(any(co.elastic.clients.elasticsearch.core.BulkRequest.class)))
        .thenThrow(new RuntimeException("bulk down"));

    assertThat(esIndexService.indexChunks(List.of(chunk(12L, 2L, 100L, "fail")), document(2L)))
        .isFalse();
  }

  @Test
  void indexChunks_moreThanBatchSize_sendsMultipleBulks() throws Exception {
    BulkResponse response = BulkResponse.of(b -> b.errors(false).items(List.of()).took(1L));
    when(client.bulk(any(co.elastic.clients.elasticsearch.core.BulkRequest.class))).thenReturn(response);
    List<DocumentChunk> chunks =
        java.util.stream.LongStream.rangeClosed(1, 55)
            .mapToObj(id -> chunk(id, 3L, 100L, "chunk-" + id))
            .toList();

    assertThat(esIndexService.indexChunks(chunks, document(3L))).isTrue();

    verify(client, times(2)).bulk(any(co.elastic.clients.elasticsearch.core.BulkRequest.class));
  }

  @Test
  void countByDocId_returnsCountOrFallback() throws Exception {
    CountResponse response =
        CountResponse.of(c -> c.count(7L).shards(s -> s.failed(0).successful(1).total(1)));
    when(client.count(any(CountRequest.class)))
        .thenReturn(response);

    assertThat(esIndexService.countByDocId(5L)).isEqualTo(7L);
    assertThat(esIndexService.countByDocId(null)).isZero();
  }

  @Test
  void countByDocId_whenClientFails_returnsNegativeOne() throws Exception {
    when(client.count(any(CountRequest.class)))
        .thenThrow(new RuntimeException("es down"));

    assertThat(esIndexService.countByDocId(9L)).isEqualTo(-1L);
  }

  @Test
  void deleteByDocId_nullOrKbId_isSafe() {
    esIndexService.deleteByDocId(null);
    esIndexService.deleteByKbId(null);
  }

  @Test
  void deleteByDocId_withNonNullId_callsDeleteByQueryAndRefresh() throws Exception {
    var dbqResponse = mock(co.elastic.clients.elasticsearch.core.DeleteByQueryResponse.class);
    when(client.deleteByQuery(any(java.util.function.Function.class))).thenReturn(dbqResponse);
    var refreshResponse = mock(co.elastic.clients.elasticsearch.indices.RefreshResponse.class);
    when(indicesClient.refresh(any(java.util.function.Function.class))).thenReturn(refreshResponse);

    esIndexService.deleteByDocId(42L);

    verify(client).deleteByQuery(any(java.util.function.Function.class));
    verify(indicesClient).refresh(any(java.util.function.Function.class));
  }

  @Test
  void deleteByDocId_whenClientFails_swallowsException() throws Exception {
    when(client.deleteByQuery(any(java.util.function.Function.class)))
        .thenThrow(new RuntimeException("ES unavailable"));

    esIndexService.deleteByDocId(99L);  // must not throw
  }

  @Test
  void deleteByKbId_withNonNullId_callsDeleteByQueryAndRefresh() throws Exception {
    var dbqResponse = mock(co.elastic.clients.elasticsearch.core.DeleteByQueryResponse.class);
    when(client.deleteByQuery(any(java.util.function.Function.class))).thenReturn(dbqResponse);
    var refreshResponse = mock(co.elastic.clients.elasticsearch.indices.RefreshResponse.class);
    when(indicesClient.refresh(any(java.util.function.Function.class))).thenReturn(refreshResponse);

    esIndexService.deleteByKbId(100L);

    verify(client).deleteByQuery(any(java.util.function.Function.class));
  }

  @Test
  void deleteByKbId_whenClientFails_swallowsException() throws Exception {
    when(client.deleteByQuery(any(java.util.function.Function.class)))
        .thenThrow(new RuntimeException("ES down"));

    esIndexService.deleteByKbId(55L);  // must not throw
  }

  @Test
  void indexChunks_withImageChunk_setsModalityField() throws Exception {
    BulkResponse response = BulkResponse.of(b -> b.errors(false).items(List.of()).took(1L));
    when(client.bulk(any(co.elastic.clients.elasticsearch.core.BulkRequest.class))).thenReturn(response);

    DocumentChunk imageChunk = chunk(20L, 5L, 200L, "image data");
    imageChunk.setChunkModality("IMAGE");
    Document doc = document(5L);
    doc.setFileType("jpg");

    assertThat(esIndexService.indexChunks(List.of(imageChunk), doc)).isTrue();
  }

  private static Document document(long id) {
    Document doc = new Document();
    doc.setId(id);
    doc.setFilename("file-" + id + ".md");
    return doc;
  }

  private static DocumentChunk chunk(Long id, long docId, long kbId, String content) {
    DocumentChunk chunk = new DocumentChunk();
    chunk.setId(id);
    chunk.setDocId(docId);
    chunk.setKbId(kbId);
    chunk.setContent(content);
    chunk.setChunkIndex(0);
    chunk.setCreatedAt(LocalDateTime.now());
    return chunk;
  }
}
