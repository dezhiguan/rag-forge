package com.ragforge.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.util.ObjectBuilder;
import com.ragforge.config.ElasticsearchClientProvider;
import com.ragforge.model.dto.SearchRequest.SearchFilter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EsSearchServiceTest {

  @Mock private ElasticsearchClient client;
  @Mock private ElasticsearchClientProvider clientProvider;

  private EsSearchService esSearchService;

  @BeforeEach
  void setUp() {
    lenient().when(clientProvider.get()).thenReturn(client);
    esSearchService = new EsSearchService(clientProvider);
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void search_mapsHitsWithFilters() throws Exception {
    stubSearchResponse(List.of(hit(sourceMap(), 2.5)));

    SearchFilter filter = new SearchFilter();
    filter.setChunkType(List.of("FAQ"));

    List<SearchResult> results =
        esSearchService.search("hello", List.of(1L), List.of(20L), 5, filter);

    assertThat(results).hasSize(1);
    SearchResult result = results.get(0);
    assertThat(result.getChunkId()).isEqualTo(10L);
    assertThat(result.getDocId()).isEqualTo(20L);
    assertThat(result.getFilename()).isEqualTo("guide.pdf");
    assertThat(result.getContent()).isEqualTo("hello world");
    assertThat(result.getChunkIndex()).isEqualTo(1);
    assertThat(result.getBm25Score()).isEqualTo(2.5);
    assertThat(result.getVectorScore()).isZero();
    assertThat(result.getChunkType()).isEqualTo("FAQ");
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void search_withoutOptionalFilters_usesFourArgOverload() throws Exception {
    stubSearchResponse(List.of(hit(sourceMap(), 1.0)));

    List<SearchResult> results = esSearchService.search("hello", List.of(1L), null, 3);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).getChunkId()).isEqualTo(10L);
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void search_multipleHitsAndNumericCoercion() throws Exception {
    Map<String, Object> first = new java.util.HashMap<>(sourceMap());
    first.put("chunk_id", 1);
    first.put("doc_id", 2L);
    first.put("chunk_index", 0);
    Map<String, Object> second = new java.util.HashMap<>();
    second.put("chunk_id", "bad");
    second.put("doc_id", "bad");
    second.put("filename", "f2");
    second.put("content", "c2");
    second.put("chunk_index", "bad");
    second.put("chunk_type", null);

    stubSearchResponse(List.of(hit(first, 0.8), hit(second, 0.5)));

    List<SearchResult> results = esSearchService.search("q", List.of(1L, 2L), List.of(3L), 10, null);

    assertThat(results).hasSize(2);
    assertThat(results.get(0).getChunkId()).isEqualTo(1L);
    assertThat(results.get(1).getChunkId()).isZero();
    assertThat(results.get(1).getChunkIndex()).isZero();
    assertThat(results.get(1).getChunkType()).isNull();
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void search_emptyHits_returnsEmptyList() throws Exception {
    stubSearchResponse(List.of());

    assertThat(esSearchService.search("q", List.of(1L), null, 5)).isEmpty();
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void search_skipsNullSourceAndHandlesMissingScore() throws Exception {
    SearchResponse<Map> response = mock(SearchResponse.class);
    HitsMetadata<Map> hitsMetadata = mock(HitsMetadata.class);
    Hit<Map> nullSourceHit = mock(Hit.class);
    Hit<Map> validHit = mock(Hit.class);
    when(nullSourceHit.source()).thenReturn(null);
    Map<String, Object> validSource = new java.util.HashMap<>();
    validSource.put("chunk_id", "bad");
    validSource.put("doc_id", null);
    when(validHit.source()).thenReturn(validSource);
    when(validHit.score()).thenReturn(null);
    when(hitsMetadata.hits()).thenReturn(List.of(nullSourceHit, validHit));
    when(response.hits()).thenReturn(hitsMetadata);
    doAnswer(
            inv -> {
              Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>> fn = inv.getArgument(0);
              fn.apply(new SearchRequest.Builder());
              return response;
            })
        .when(client)
        .search(
            org.mockito.ArgumentMatchers.<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>>any(),
            eq(Map.class));

    List<SearchResult> results = esSearchService.search("q", List.of(1L), null, 3);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).getChunkId()).isZero();
    assertThat(results.get(0).getDocId()).isZero();
    assertThat(results.get(0).getBm25Score()).isZero();
    assertThat(results.get(0).getContent()).isEmpty();
  }

  @Test
  void search_whenClientThrows_returnsEmptyList() throws Exception {
    when(client.search(
            org.mockito.ArgumentMatchers.<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>>any(),
            eq(Map.class)))
        .thenThrow(new RuntimeException("es down"));

    List<SearchResult> results = esSearchService.search("q", List.of(1L), null, 5);

    assertThat(results).isEmpty();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void stubSearchResponse(List<Hit<Map>> hits) throws Exception {
    SearchResponse<Map> response = mock(SearchResponse.class);
    HitsMetadata<Map> hitsMetadata = mock(HitsMetadata.class);
    when(hitsMetadata.hits()).thenReturn(hits);
    when(response.hits()).thenReturn(hitsMetadata);
    doAnswer(
            inv -> {
              Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>> fn = inv.getArgument(0);
              fn.apply(new SearchRequest.Builder());
              return response;
            })
        .when(client)
        .search(
            org.mockito.ArgumentMatchers.<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>>any(),
            eq(Map.class));
  }

  @SuppressWarnings("unchecked")
  private static Hit<Map> hit(Map<String, Object> source, Double score) {
    Hit<Map> hit = mock(Hit.class);
    when(hit.source()).thenReturn(source);
    when(hit.score()).thenReturn(score);
    return hit;
  }

  private static Map<String, Object> sourceMap() {
    Map<String, Object> source = new java.util.HashMap<>();
    source.put("chunk_id", 10L);
    source.put("doc_id", 20);
    source.put("filename", "guide.pdf");
    source.put("content", "hello world");
    source.put("chunk_index", 1);
    source.put("chunk_type", "FAQ");
    return source;
  }
}
