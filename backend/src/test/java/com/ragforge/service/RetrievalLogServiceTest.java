package com.ragforge.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.mapper.RetrievalLogMapper;
import com.ragforge.model.entity.RetrievalLog;
import com.ragforge.search.SearchResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RetrievalLogServiceTest {

  @Mock private RetrievalLogMapper retrievalLogMapper;

  private RetrievalLogService retrievalLogService;

  @BeforeEach
  void setUp() {
    retrievalLogService = new RetrievalLogService(retrievalLogMapper, new ObjectMapper());
  }

  @Test
  void logAsync_persistsRetrievalLogWithKbIdsAndRewrites() {
    retrievalLogService.logAsync(
        "spring boot",
        "hybrid",
        List.of(1L, 2L),
        List.of("spring", "boot framework"),
        10,
        3,
        150L);

    ArgumentCaptor<RetrievalLog> captor = ArgumentCaptor.forClass(RetrievalLog.class);
    verify(retrievalLogMapper).insert(captor.capture());

    RetrievalLog log = captor.getValue();
    org.assertj.core.api.Assertions.assertThat(log.getQuery()).isEqualTo("spring boot");
    org.assertj.core.api.Assertions.assertThat(log.getStrategy()).isEqualTo("hybrid");
    org.assertj.core.api.Assertions.assertThat(log.getKbIds()).isEqualTo("1,2");
    org.assertj.core.api.Assertions.assertThat(log.getRewrittenQueries()).contains("spring");
    org.assertj.core.api.Assertions.assertThat(log.getTopK()).isEqualTo(10);
    org.assertj.core.api.Assertions.assertThat(log.getResultCount()).isEqualTo(3);
    org.assertj.core.api.Assertions.assertThat(log.getLatencyMs()).isEqualTo(150);
    org.assertj.core.api.Assertions.assertThat(log.getCreatedAt()).isNotNull();
  }

  @Test
  void logAsync_allowsNullKbIdsAndRewrites() {
    retrievalLogService.logAsync("q", "vector", null, null, 5, 0, 20L);

    ArgumentCaptor<RetrievalLog> captor = ArgumentCaptor.forClass(RetrievalLog.class);
    verify(retrievalLogMapper).insert(captor.capture());

    RetrievalLog log = captor.getValue();
    org.assertj.core.api.Assertions.assertThat(log.getKbIds()).isNull();
    org.assertj.core.api.Assertions.assertThat(log.getRewrittenQueries()).isNull();
  }

  @Test
  void logAsync_persistsCitationSnapshotWithTextLimitedTo300Chars() {
    SearchResult hit = new SearchResult();
    hit.setChunkId(9912L);
    hit.setDocId(882L);
    hit.setContent("前".repeat(350));
    hit.setFinalScore(0.81);

    retrievalLogService.logAsync("q", "hybrid", List.of(1L), null, 5, 1, 30L, List.of(hit));

    ArgumentCaptor<RetrievalLog> captor = ArgumentCaptor.forClass(RetrievalLog.class);
    verify(retrievalLogMapper).insert(captor.capture());

    RetrievalLog log = captor.getValue();
    org.assertj.core.api.Assertions.assertThat(log.getCitationsSnapshot()).contains("\"chunkId\":9912");
    org.assertj.core.api.Assertions.assertThat(log.getCitationsSnapshot()).contains("\"docId\":882");
    org.assertj.core.api.Assertions.assertThat(log.getCitationsSnapshot()).contains("\"score\":0.81");
    org.assertj.core.api.Assertions.assertThat(log.getCitationsSnapshot())
        .contains("\"textSnippet\":\"" + "前".repeat(300) + "\"");
  }

  @Test
  void logAsync_persistsUserOrgAndAvgRerankScore() {
    retrievalLogService.logAsync(
        "q", "hybrid", List.of(3L), List.of("rewrite"), 8, 2, 45L, List.of(), 11L, 0.73, 22L);

    ArgumentCaptor<RetrievalLog> captor = ArgumentCaptor.forClass(RetrievalLog.class);
    verify(retrievalLogMapper).insert(captor.capture());

    RetrievalLog log = captor.getValue();
    org.assertj.core.api.Assertions.assertThat(log.getUserId()).isEqualTo(11L);
    org.assertj.core.api.Assertions.assertThat(log.getOrgId()).isEqualTo(22L);
    org.assertj.core.api.Assertions.assertThat(log.getAvgRerankScore()).isEqualTo(0.73);
    org.assertj.core.api.Assertions.assertThat(log.getStatus()).isEqualTo("SUCCESS");
  }

  @Test
  void logAsync_citationSnapshotUsesBm25ThenVectorFallbackScores() {
    SearchResult bm25 = new SearchResult();
    bm25.setChunkId(1L);
    bm25.setDocId(10L);
    bm25.setContent("bm25");
    bm25.setBm25Score(0.44);

    SearchResult vector = new SearchResult();
    vector.setChunkId(2L);
    vector.setDocId(20L);
    vector.setContent("vector");
    vector.setVectorScore(0.33);

    retrievalLogService.logAsync("q", "hybrid", null, null, 5, 2, 30L, List.of(bm25, vector));

    ArgumentCaptor<RetrievalLog> captor = ArgumentCaptor.forClass(RetrievalLog.class);
    verify(retrievalLogMapper).insert(captor.capture());

    org.assertj.core.api.Assertions.assertThat(captor.getValue().getCitationsSnapshot())
        .contains("\"score\":0.44")
        .contains("\"score\":0.33");
  }

  @Test
  void logFailureAsync_persistsErrorEntry() {
    retrievalLogService.logFailureAsync("q", "vector", List.of(9L, 10L), 77L, 13L, 15L);

    ArgumentCaptor<RetrievalLog> captor = ArgumentCaptor.forClass(RetrievalLog.class);
    verify(retrievalLogMapper).insert(captor.capture());

    RetrievalLog log = captor.getValue();
    org.assertj.core.api.Assertions.assertThat(log.getUserId()).isEqualTo(13L);
    org.assertj.core.api.Assertions.assertThat(log.getOrgId()).isEqualTo(15L);
    org.assertj.core.api.Assertions.assertThat(log.getKbIds()).isEqualTo("9,10");
    org.assertj.core.api.Assertions.assertThat(log.getResultCount()).isZero();
    org.assertj.core.api.Assertions.assertThat(log.getLatencyMs()).isEqualTo(77);
    org.assertj.core.api.Assertions.assertThat(log.getStatus()).isEqualTo("ERROR");
  }

  @Test
  void logFailureAsync_swallowsMapperFailure() {
    doThrow(new RuntimeException("db down")).when(retrievalLogMapper).insert(any(RetrievalLog.class));

    retrievalLogService.logFailureAsync("q", "vector", null, 1L, null, null);

    verify(retrievalLogMapper).insert(any(RetrievalLog.class));
  }
}
