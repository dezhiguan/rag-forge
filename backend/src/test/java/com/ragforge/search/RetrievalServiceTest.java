package com.ragforge.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.common.BizException;
import com.ragforge.config.RetrievalProperties;
import com.ragforge.metrics.RagforgeMetrics;
import com.ragforge.search.HybridSearchService.HybridSearchOutput;
import com.ragforge.search.RerankerClient.RerankOutput;
import com.ragforge.search.RerankerClient.RerankResult;
import com.ragforge.search.RetrievalService.RetrievalOutput;
import com.ragforge.search.limit.LocalConcurrencyLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

  @Mock private VectorSearchService vectorSearchService;
  @Mock private EsSearchService esSearchService;
  @Mock private HybridSearchService hybridSearchService;
  @Mock private QueryRewriter queryRewriter;
  @Mock private RerankerClient rerankerClient;

  private RetrievalProperties retrievalProperties;
  private SimpleMeterRegistry meterRegistry;
  private ExecutorService retrievalExecutor;
  private RetrievalService retrievalService;

  @BeforeEach
  void setUp() {
    retrievalProperties = new RetrievalProperties();
    retrievalProperties.getVector().setMaxConcurrent(1);
    retrievalProperties.getVector().setTimeoutMs(5000);
    meterRegistry = new SimpleMeterRegistry();
    retrievalExecutor = Executors.newCachedThreadPool();
    retrievalService =
        new RetrievalService(
            vectorSearchService,
            esSearchService,
            hybridSearchService,
            queryRewriter,
            rerankerClient,
            meterRegistry,
            new RagforgeMetrics(meterRegistry),
            retrievalProperties,
            retrievalExecutor,
            new LocalConcurrencyLimiter());
  }

  @AfterEach
  void tearDown() {
    retrievalExecutor.shutdownNow();
  }

  @Test
  void keywordStrategy_usesEsSearchOnly() {
    SearchResult hit = result(10L, 0.0, 0.75);
    when(esSearchService.search(eq("spring"), anyList(), any(), eq(5), any()))
        .thenReturn(List.of(hit));

    RetrievalOutput output =
        retrievalService.retrieve("spring", List.of(1L), null, "keyword", null, 5, 5);

    assertThat(output.getStrategy()).isEqualTo("keyword");
    assertThat(output.getResults()).hasSize(1);
    assertThat(output.getKeywordLatencyMs()).isNotNull();
    verify(vectorSearchService, never()).search(anyString(), anyList(), any(), anyInt(), any());
  }

  @Test
  void defaultStrategy_usesVectorSearch() {
    SearchResult hit = result(11L, 0.88, 0.0);
    when(vectorSearchService.search(eq("embedding"), anyList(), any(), eq(5), any()))
        .thenReturn(List.of(hit));

    RetrievalOutput output =
        retrievalService.retrieve("embedding", List.of(1L), null, null, null, 5, 5);

    assertThat(output.getStrategy()).isEqualTo("vector");
    assertThat(output.getResults().get(0).getChunkId()).isEqualTo(11L);
    verify(esSearchService, never()).search(anyString(), anyList(), any(), anyInt(), any());
  }

  @Test
  void hybridStrategy_delegatesToHybridSearchService() {
    SearchResult hit = result(12L, 0.6, 0.4);
    hit.setFinalScore(0.7);
    HybridSearchOutput hybridOutput =
        new HybridSearchOutput(List.of(hit), 10L, 20L, "hybrid");
    when(hybridSearchService.searchWithMetrics(
            eq("hybrid-q"), anyList(), any(), eq(5), eq(0.55), any()))
        .thenReturn(hybridOutput);

    RetrievalOutput output =
        retrievalService.retrieve("hybrid-q", List.of(1L), null, "hybrid", null, 5, 5);

    assertThat(output.getStrategy()).isEqualTo("hybrid");
    assertThat(output.getVectorLatencyMs()).isEqualTo(10L);
    assertThat(output.getKeywordLatencyMs()).isEqualTo(20L);
    verify(hybridSearchService)
        .searchWithMetrics(eq("hybrid-q"), anyList(), any(), eq(5), eq(0.55), any());
  }

  @Test
  void rewriteStrategy_usesRewrittenQueriesForVectorSearch() {
    when(queryRewriter.rewrite("original")).thenReturn(List.of("original", "variant-a"));
    SearchResult hit = result(13L, 0.77, 0.0);
    when(vectorSearchService.searchByQueries(
            eq(List.of("original", "variant-a")), anyList(), any(), eq(5), any()))
        .thenReturn(List.of(hit));

    RetrievalOutput output =
        retrievalService.retrieve("original", List.of(1L), null, "rewrite", null, 5, 5);

    assertThat(output.getStrategy()).isEqualTo("rewrite");
    assertThat(output.getRewrittenQueries()).containsExactly("original", "variant-a");
    assertThat(output.getRewriteLatencyMs()).isNotNull();
  }

  @Test
  void fullStrategy_appliesRerankOrdering() {
    when(queryRewriter.rewrite("full-q")).thenReturn(List.of("full-q", "full-q-alt"));

    SearchResult first = result(1L, 0.5, 0.3);
    first.setContent("aaa");
    SearchResult second = result(2L, 0.4, 0.6);
    second.setContent("bbb");
    when(vectorSearchService.searchByQueries(anyList(), anyList(), any(), anyInt(), any()))
        .thenReturn(List.of(first, second));
    when(esSearchService.search(anyString(), anyList(), any(), anyInt(), any()))
        .thenReturn(List.of(second, first));

    // After RRF merge, chunk 2 ranks before chunk 1; rerank indices refer to that merged list.
    RerankResult top = new RerankResult();
    top.setIndex(0);
    top.setScore(0.99);
    RerankResult rest = new RerankResult();
    rest.setIndex(1);
    rest.setScore(0.50);
    when(rerankerClient.rerank(eq("full-q"), anyList(), anyInt()))
        .thenReturn(new RerankOutput(List.of(top, rest), 15L));

    RetrievalOutput output =
        retrievalService.retrieve("full-q", List.of(1L), null, "full", 0.55, 2, 2);

    assertThat(output.getStrategy()).isEqualTo("full");
    assertThat(output.getRerankLatencyMs()).isEqualTo(15L);
    assertThat(output.getResults()).hasSize(2);
    assertThat(output.getResults().get(0).getChunkId()).isEqualTo(2L);
    assertThat(output.getResults().get(0).getFinalScore()).isEqualTo(0.99);
  }

  @Test
  void concurrentOverload_returns429() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(vectorSearchService.search(anyString(), anyList(), any(), anyInt(), any()))
        .thenAnswer(
            inv -> {
              started.countDown();
              release.await(3, TimeUnit.SECONDS);
              return List.of();
            });

    AtomicReference<RetrievalOutput> firstResult = new AtomicReference<>();
    AtomicReference<Throwable> secondError = new AtomicReference<>();

    Thread first =
        new Thread(
            () ->
                firstResult.set(
                    retrievalService.retrieve("slow", List.of(1L), null, "vector", null, 5, 5)));
    first.start();
    assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();

    Thread second =
        new Thread(
            () -> {
              try {
                retrievalService.retrieve("blocked", List.of(1L), null, "vector", null, 5, 5);
              } catch (Throwable t) {
                secondError.set(t);
              }
            });
    second.start();
    second.join(3000);

    release.countDown();
    first.join(3000);

    assertThat(secondError.get()).isInstanceOf(BizException.class);
    assertThat(((BizException) secondError.get()).getCode()).isEqualTo(429);
    assertThat(meterRegistry.counter("ragforge.retrieval.rejected", "strategy", "vector").count())
        .isEqualTo(1.0);
  }

  @Test
  void vectorWeightIsClampedForHybrid() {
    HybridSearchOutput hybridOutput =
        new HybridSearchOutput(List.of(), null, null, "hybrid");
    when(hybridSearchService.searchWithMetrics(
            eq("w"), anyList(), any(), eq(5), eq(1.0), any()))
        .thenReturn(hybridOutput);

    retrievalService.retrieve("w", List.of(1L), null, "hybrid", 2.0, 5, 5);

    verify(hybridSearchService)
        .searchWithMetrics(eq("w"), anyList(), any(), eq(5), eq(1.0), any());
  }

  private static SearchResult result(long chunkId, double vectorScore, double bm25Score) {
    SearchResult r = new SearchResult();
    r.setChunkId(chunkId);
    r.setDocId(chunkId);
    r.setFilename("f-" + chunkId);
    r.setContent("c-" + chunkId);
    r.setVectorScore(vectorScore);
    r.setBm25Score(bm25Score);
    return r;
  }
}
