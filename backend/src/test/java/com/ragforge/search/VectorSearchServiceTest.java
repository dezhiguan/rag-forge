package com.ragforge.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.model.dto.SearchRequest.SearchFilter;
import com.ragforge.pipeline.embedder.EmbeddingService;
import com.ragforge.pipeline.image.ImageEmbeddingClient;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
class VectorSearchServiceTest {

  @Mock private EmbeddingService embedder;
  @Mock private ImageEmbeddingClient imageEmbeddingClient;
  @Mock private JdbcTemplate jdbcTemplate;

  private VectorSearchService vectorSearchService;

  @BeforeEach
  void setUp() {
    vectorSearchService = new VectorSearchService(embedder, imageEmbeddingClient, jdbcTemplate);
  }

  @Test
  void search_appliesKbDocAndChunkTypeFilters() throws Exception {
    when(embedder.embed("query")).thenReturn(new float[] {0.1f, 0.2f});
    when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              PreparedStatementSetter pss = inv.getArgument(1);
              RowMapper<SearchResult> mapper = inv.getArgument(2);

              Connection conn = mock(Connection.class);
              PreparedStatement ps = mock(PreparedStatement.class);
              Array sqlArray = mock(Array.class);
              when(ps.getConnection()).thenReturn(conn);
              when(conn.createArrayOf("varchar", new String[] {"RESUME"})).thenReturn(sqlArray);
              pss.setValues(ps);

              ResultSet rs = mock(ResultSet.class);
              when(rs.getLong("id")).thenReturn(11L);
              when(rs.getString("content")).thenReturn("chunk text");
              when(rs.getLong("doc_id")).thenReturn(5L);
              when(rs.getString("filename")).thenReturn("cv.pdf");
              when(rs.getInt("chunk_index")).thenReturn(2);
              when(rs.getDouble("similarity")).thenReturn(0.88);
              when(rs.getString("chunk_type")).thenReturn("RESUME");
              return List.of(mapper.mapRow(rs, 0));
            });

    SearchFilter filter = new SearchFilter();
    filter.setChunkType(List.of("RESUME"));

    List<SearchResult> results =
        vectorSearchService.search("query", List.of(1L, 2L), List.of(9L), 5, filter);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).getChunkId()).isEqualTo(11L);
    assertThat(results.get(0).getVectorScore()).isEqualTo(0.88);
    assertThat(results.get(0).getChunkType()).isEqualTo("RESUME");

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).query(sqlCaptor.capture(), any(PreparedStatementSetter.class), any(RowMapper.class));
    assertThat(sqlCaptor.getValue()).contains("dc.kb_id IN");
    assertThat(sqlCaptor.getValue()).contains("dc.doc_id IN");
    assertThat(sqlCaptor.getValue()).contains("chunk_type = ANY");
  }

  @Test
  void search_withoutFilters_stillReturnsMappedRows() throws Exception {
    when(embedder.embed("hello")).thenReturn(new float[] {0.3f});
    when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              PreparedStatementSetter pss = inv.getArgument(1);
              RowMapper<SearchResult> mapper = inv.getArgument(2);
              PreparedStatement ps = mock(PreparedStatement.class);
              pss.setValues(ps);

              ResultSet rs = mock(ResultSet.class);
              when(rs.getLong("id")).thenReturn(1L);
              when(rs.getString("content")).thenReturn("c");
              when(rs.getLong("doc_id")).thenReturn(2L);
              when(rs.getString("filename")).thenReturn("f.md");
              when(rs.getInt("chunk_index")).thenReturn(0);
              when(rs.getDouble("similarity")).thenReturn(0.5);
              when(rs.getString("chunk_type")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });

    List<SearchResult> results = vectorSearchService.search("hello", List.of(1L), null, 3);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).getFilename()).isEqualTo("f.md");
  }

  @Test
  void searchByQueries_emptyInput_returnsEmptyList() {
    assertThat(vectorSearchService.searchByQueries(List.of(), List.of(1L), null, 5)).isEmpty();
    assertThat(vectorSearchService.searchByQueries(null, List.of(1L), null, 5)).isEmpty();
  }

  @Test
  void searchByQueries_deduplicatesByHighestScoreAndTruncatesTopK() throws Exception {
    when(embedder.embedBatch(List.of("q1", "q2")))
        .thenReturn(List.of(new float[] {0.1f}, new float[] {0.2f}));
    when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<SearchResult> mapper = inv.getArgument(2);
              ResultSet rs1 = row(100L, 0.6);
              ResultSet rs2 = row(100L, 0.9);
              ResultSet rs3 = row(200L, 0.7);
              return List.of(mapper.mapRow(rs1, 0), mapper.mapRow(rs2, 1), mapper.mapRow(rs3, 2));
            });

    List<SearchResult> results =
        vectorSearchService.searchByQueries(List.of("q1", "q2"), List.of(1L), List.of(3L), 1);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).getChunkId()).isEqualTo(100L);
    assertThat(results.get(0).getVectorScore()).isEqualTo(0.9);
  }

  @Test
  void searchByQueries_embeddingCountMismatch_throws() {
    when(embedder.embedBatch(List.of("q1"))).thenReturn(List.of());

    assertThatThrownBy(
            () -> vectorSearchService.searchByQueries(List.of("q1"), List.of(1L), null, 5))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Embedding 数量");
  }

  @Test
  void searchByQueries_skipsNullChunkIds() throws Exception {
    when(embedder.embedBatch(List.of("q1"))).thenReturn(List.of(new float[] {0.1f}));
    when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<SearchResult> mapper = inv.getArgument(2);
              SearchResult nullId = new SearchResult();
              nullId.setChunkId(null);
              nullId.setVectorScore(0.1);
              ResultSet rs = row(300L, 0.4);
              return List.of(nullId, mapper.mapRow(rs, 1));
            });

    List<SearchResult> results =
        vectorSearchService.searchByQueries(List.of("q1"), List.of(1L), null, 5, null);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).getChunkId()).isEqualTo(300L);
  }

  private static ResultSet row(long chunkId, double score) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getLong("id")).thenReturn(chunkId);
    when(rs.getString("content")).thenReturn("content-" + chunkId);
    when(rs.getLong("doc_id")).thenReturn(chunkId);
    when(rs.getString("filename")).thenReturn("file-" + chunkId);
    when(rs.getInt("chunk_index")).thenReturn(0);
    when(rs.getDouble("similarity")).thenReturn(score);
    when(rs.getString("chunk_type")).thenReturn("TEXT");
    return rs;
  }
}
