package com.ragforge.pipeline.indexer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.transport.endpoints.BooleanResponse;
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

  private EsIndexService esIndexService;

  @BeforeEach
  void setUp() {
    esIndexService = new EsIndexService(client);
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
  void indexChunks_emptyInput_returnsTrue() {
    assertThat(esIndexService.indexChunks(List.of(), document(1L))).isTrue();
    assertThat(esIndexService.indexChunks(null, document(1L))).isTrue();
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
