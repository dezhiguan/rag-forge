package com.ragforge.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.config.RetrievalProperties;
import com.ragforge.search.HybridSearchService.HybridSearchOutput;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HybridSearchServiceTest {

  @Mock private VectorSearchService vectorSearchService;
  @Mock private EsSearchService esSearchService;

  private RetrievalProperties retrievalProperties;
  private HybridSearchService hybridSearchService;

  @BeforeEach
  void setUp() {
    retrievalProperties = new RetrievalProperties();
    retrievalProperties.setStageTimeoutMs(8000);
    hybridSearchService =
        new HybridSearchService(
            vectorSearchService,
            esSearchService,
            retrievalProperties,
            Runnable::run);
  }

  @Test
  void vectorWeightOne_degradesToVectorOnly() {
    SearchResult r = result(1L, 0.9, 0.0);
    when(vectorSearchService.search(eq("q"), anyList(), any(), eq(5), any()))
        .thenReturn(List.of(r));

    HybridSearchOutput output =
        hybridSearchService.searchWithMetrics("q", List.of(1L), null, 5, 1.0);

    assertThat(output.getEffectiveStrategy()).isEqualTo("vector");
    assertThat(output.getKeywordLatencyMs()).isNull();
    assertThat(output.getResults()).hasSize(1);
    assertThat(output.getResults().get(0).getFinalScore()).isEqualTo(0.9);
    verify(esSearchService, never()).search(any(), anyList(), any(), anyInt(), any());
  }

  @Test
  void vectorWeightZero_degradesToKeywordOnly() {
    SearchResult r = result(2L, 0.0, 0.8);
    when(esSearchService.search(eq("q"), anyList(), any(), eq(5), any()))
        .thenReturn(List.of(r));

    HybridSearchOutput output =
        hybridSearchService.searchWithMetrics("q", List.of(1L), null, 5, 0.0);

    assertThat(output.getEffectiveStrategy()).isEqualTo("keyword");
    assertThat(output.getVectorLatencyMs()).isNull();
    assertThat(output.getResults()).hasSize(1);
    assertThat(output.getResults().get(0).getFinalScore()).isEqualTo(0.8);
    verify(vectorSearchService, never()).search(any(), anyList(), any(), anyInt(), any());
  }

  @Test
  void hybrid_rrfMergesAndRanksByCombinedScore() {
    SearchResult vectorFirst = result(1L, 0.5, 0.0);
    SearchResult vectorSecond = result(2L, 0.4, 0.0);
    SearchResult keywordFirst = result(2L, 0.0, 0.7);
    SearchResult keywordSecond = result(3L, 0.0, 0.6);

    when(vectorSearchService.search(eq("q"), anyList(), any(), eq(20), any()))
        .thenReturn(List.of(vectorFirst, vectorSecond));
    when(esSearchService.search(eq("q"), anyList(), any(), eq(20), any()))
        .thenReturn(List.of(keywordFirst, keywordSecond));

    HybridSearchOutput output =
        hybridSearchService.searchWithMetrics("q", List.of(1L), null, 5, 0.55);

    assertThat(output.getEffectiveStrategy()).isEqualTo("hybrid");
    assertThat(output.getResults()).hasSize(3);
    // chunk 2 appears in both lists → highest RRF
    assertThat(output.getResults().get(0).getChunkId()).isEqualTo(2L);
  }

  @Test
  void hybrid_truncatesToTopK() {
    List<SearchResult> vectorResults =
        List.of(result(1L, 0.9, 0.0), result(2L, 0.8, 0.0), result(3L, 0.7, 0.0));
    when(vectorSearchService.search(any(), anyList(), any(), eq(20), any()))
        .thenReturn(vectorResults);
    when(esSearchService.search(any(), anyList(), any(), eq(20), any()))
        .thenReturn(List.of());

    HybridSearchOutput output =
        hybridSearchService.searchWithMetrics("q", List.of(1L), null, 2, 0.55);

    assertThat(output.getResults()).hasSize(2);
  }

  @Test
  void hybrid_vectorTimeout_degradesToKeywordResults() throws Exception {
    retrievalProperties.setStageTimeoutMs(100);
    hybridSearchService =
        new HybridSearchService(
            vectorSearchService,
            esSearchService,
            retrievalProperties,
            Executors.newCachedThreadPool());

    when(vectorSearchService.search(any(), anyList(), any(), anyInt(), any()))
        .thenAnswer(
            inv -> {
              Thread.sleep(500);
              return List.of(result(1L, 0.9, 0.0));
            });
    SearchResult keywordHit = result(2L, 0.0, 0.85);
    when(esSearchService.search(any(), anyList(), any(), anyInt(), any()))
        .thenReturn(List.of(keywordHit));

    HybridSearchOutput output =
        hybridSearchService.searchWithMetrics("q", List.of(1L), null, 5, 0.55);

    assertThat(output.getResults()).hasSize(1);
    assertThat(output.getResults().get(0).getChunkId()).isEqualTo(2L);
  }

  @Test
  void hybrid_keywordTimeout_degradesToVectorResults() throws Exception {
    retrievalProperties.setStageTimeoutMs(100);
    hybridSearchService =
        new HybridSearchService(
            vectorSearchService,
            esSearchService,
            retrievalProperties,
            Executors.newCachedThreadPool());

    SearchResult vectorHit = result(1L, 0.92, 0.0);
    when(vectorSearchService.search(any(), anyList(), any(), anyInt(), any()))
        .thenReturn(List.of(vectorHit));
    when(esSearchService.search(any(), anyList(), any(), anyInt(), any()))
        .thenAnswer(
            inv -> {
              Thread.sleep(500);
              return List.of(result(2L, 0.0, 0.85));
            });

    HybridSearchOutput output =
        hybridSearchService.searchWithMetrics("q", List.of(1L), null, 5, 0.55);

    assertThat(output.getEffectiveStrategy()).isEqualTo("hybrid");
    assertThat(output.getResults()).hasSize(1);
    assertThat(output.getResults().get(0).getChunkId()).isEqualTo(1L);
  }

  @Test
  void hybrid_bothPathsTimeout_returnsEmptyResults() throws Exception {
    retrievalProperties.setStageTimeoutMs(100);
    hybridSearchService =
        new HybridSearchService(
            vectorSearchService,
            esSearchService,
            retrievalProperties,
            command -> {});

    HybridSearchOutput output =
        hybridSearchService.searchWithMetrics("q", List.of(1L), null, 5, 0.55);

    assertThat(output.getEffectiveStrategy()).isEqualTo("hybrid");
    assertThat(output.getResults()).isEmpty();
  }

  private static SearchResult result(long chunkId, double vectorScore, double bm25Score) {
    SearchResult r = new SearchResult();
    r.setChunkId(chunkId);
    r.setDocId(chunkId);
    r.setFilename("doc-" + chunkId + ".pdf");
    r.setContent("content-" + chunkId);
    r.setVectorScore(vectorScore);
    r.setBm25Score(bm25Score);
    return r;
  }
}
