package com.ragforge.service;

import static org.mockito.ArgumentMatchers.any;
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
}
