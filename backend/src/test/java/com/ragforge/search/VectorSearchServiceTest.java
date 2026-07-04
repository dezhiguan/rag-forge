package com.ragforge.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.model.dto.SearchRequest.SearchFilter;
import com.ragforge.pipeline.embedder.EmbeddingInput;
import com.ragforge.pipeline.embedder.EmbeddingService;
import com.ragforge.pipeline.embedder.VlEmbeddingClient;
import com.ragforge.search.QdrantVectorStore.ScoredChunk;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class VectorSearchServiceTest {

  @Mock private EmbeddingService embedder;
  @Mock private VlEmbeddingClient vlEmbeddingClient;
  @Mock private JdbcTemplate jdbcTemplate;
  @Mock private QdrantVectorStore qdrantVectorStore;

  private VectorSearchService service;

  @BeforeEach
  void setUp() {
    // 缓存置为禁用（纯透传），保留既有 embedder.embed 调用断言
    QueryEmbeddingCache passthroughCache =
        new QueryEmbeddingCache(
            false, 100, 60, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    service =
        new VectorSearchService(
            embedder, vlEmbeddingClient, jdbcTemplate, qdrantVectorStore, passthroughCache);
  }

  /** 模拟一行 document_chunks 结果。 */
  private ResultSet row(long id, String content, long docId) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getLong("id")).thenReturn(id);
    when(rs.getString("content")).thenReturn(content);
    when(rs.getLong("doc_id")).thenReturn(docId);
    when(rs.getString("filename")).thenReturn("f-" + docId);
    when(rs.getInt("chunk_index")).thenReturn((int) id);
    when(rs.getString("chunk_type")).thenReturn("TEXT");
    when(rs.getString("chunk_modality")).thenReturn("TEXT");
    when(rs.getString("image_key")).thenReturn(null);
    return rs;
  }

  /** 让 jdbcTemplate.query 回放给定 id 的行，驱动 RowMapper 的 byId 副作用。 */
  private void stubHydrate(long... ids) {
    when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<SearchResult> m = inv.getArgument(2);
              List<SearchResult> out = new ArrayList<>();
              int n = 0;
              for (long id : ids) {
                out.add(m.mapRow(row(id, "c" + id, 10L), n++));
              }
              return out;
            });
  }

  @Test
  void search_queriesQdrantThenHydratesAndKeepsScoreOrder() {
    when(embedder.embed("q")).thenReturn(new float[] {0.1f, 0.2f});
    when(qdrantVectorStore.search(any(), eq(List.of(1L)), any(), any(), eq(5)))
        .thenReturn(List.of(new ScoredChunk(1L, 0.9), new ScoredChunk(2L, 0.7)));
    stubHydrate(1L, 2L);

    List<SearchResult> results = service.search("q", List.of(1L), null, 5);

    assertThat(results).hasSize(2);
    assertThat(results.get(0).getChunkId()).isEqualTo(1L);
    assertThat(results.get(0).getVectorScore()).isEqualTo(0.9);
    assertThat(results.get(1).getChunkId()).isEqualTo(2L);
    assertThat(results.get(1).getVectorScore()).isEqualTo(0.7);
  }

  @Test
  @SuppressWarnings("unchecked")
  void search_passesChunkTypeFilterToQdrant() {
    when(embedder.embed("q")).thenReturn(new float[] {0.1f});
    when(qdrantVectorStore.search(any(), any(), any(), any(), anyInt())).thenReturn(List.of());
    SearchFilter filter = new SearchFilter();
    filter.setChunkType(List.of("IMAGE"));

    service.search("q", List.of(1L), List.of(9L), 3, filter);

    ArgumentCaptor<List<String>> typeCap = ArgumentCaptor.forClass(List.class);
    verify(qdrantVectorStore)
        .search(any(), eq(List.of(1L)), eq(List.of(9L)), typeCap.capture(), eq(3));
    assertThat(typeCap.getValue()).containsExactly("IMAGE");
  }

  @Test
  void search_skipsHitsMissingInPg() {
    when(embedder.embed("q")).thenReturn(new float[] {0.1f});
    when(qdrantVectorStore.search(any(), any(), any(), any(), anyInt()))
        .thenReturn(List.of(new ScoredChunk(1L, 0.9), new ScoredChunk(404L, 0.5)));
    stubHydrate(1L); // 只有 chunk 1 在 PG，404 已删

    List<SearchResult> results = service.search("q", List.of(1L), null, 5);

    assertThat(results).extracting(SearchResult::getChunkId).containsExactly(1L);
  }

  @Test
  void searchByQueries_dedupsByMaxScoreAcrossQueries() {
    when(embedder.embedBatch(List.of("a", "b")))
        .thenReturn(List.of(new float[] {0.1f}, new float[] {0.2f}));
    when(qdrantVectorStore.search(any(), any(), any(), any(), anyInt()))
        .thenReturn(List.of(new ScoredChunk(1L, 0.6)))
        .thenReturn(List.of(new ScoredChunk(1L, 0.9), new ScoredChunk(2L, 0.8)));
    stubHydrate(1L, 2L);

    List<SearchResult> results = service.searchByQueries(List.of("a", "b"), List.of(1L), null, 5);

    assertThat(results).hasSize(2);
    assertThat(results.get(0).getChunkId()).isEqualTo(1L);
    assertThat(results.get(0).getVectorScore()).isEqualTo(0.9); // 取跨 query 最高分
  }

  @Test
  void searchByQueries_emptyReturnsEmpty() {
    assertThat(service.searchByQueries(List.of(), List.of(1L), null, 5)).isEmpty();
  }

  @Test
  void searchImage_embedsImageAndQueriesQdrant() {
    when(vlEmbeddingClient.embed(any())).thenReturn(List.of(new float[] {0.3f}));
    when(qdrantVectorStore.search(any(), any(), any(), any(), anyInt()))
        .thenReturn(List.of(new ScoredChunk(7L, 0.95)));
    stubHydrate(7L);

    List<SearchResult> results =
        service.searchImage(null, "data:image/png;base64,QUJD", List.of(1L), null, 5, null);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).getChunkId()).isEqualTo(7L);
    verify(vlEmbeddingClient).embed(any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void searchImage_fallsBackToTextWhenNoImage() {
    when(vlEmbeddingClient.embed(any())).thenReturn(List.of(new float[] {0.3f}));
    when(qdrantVectorStore.search(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

    service.searchImage("hello", null, List.of(1L), null, 5, null);

    ArgumentCaptor<List<EmbeddingInput>> cap = ArgumentCaptor.forClass(List.class);
    verify(vlEmbeddingClient).embed(cap.capture());
    assertThat(cap.getValue()).hasSize(1);
    assertThat(cap.getValue().get(0).isImage()).isFalse();
  }

  @Test
  void search_rejectsEmptyKbScope() {
    assertThatThrownBy(() -> service.search("q", List.of(), null, 5))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("kbIds");
  }
}
