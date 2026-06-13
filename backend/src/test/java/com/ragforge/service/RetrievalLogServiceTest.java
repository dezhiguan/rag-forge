package com.ragforge.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.ragforge.mapper.RetrievalLogMapper;
import com.ragforge.model.entity.RetrievalLog;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RetrievalLogServiceTest {

  @Mock private RetrievalLogMapper retrievalLogMapper;

  @InjectMocks private RetrievalLogService retrievalLogService;

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
}
