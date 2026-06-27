package com.ragforge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.common.Result;
import com.ragforge.model.dto.SearchRequest;
import com.ragforge.search.RetrievalService;
import com.ragforge.search.RetrievalService.RetrievalOutput;
import com.ragforge.search.VectorSearchService;
import com.ragforge.security.KbAccessGuard;
import com.ragforge.service.RetrievalLogService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SearchAuthorizationTest {

  @Mock private RetrievalService retrievalService;
  @Mock private VectorSearchService vectorSearchService;
  @Mock private KbAccessGuard kbAccessGuard;
  @Mock private RetrievalLogService retrievalLogService;

  @Test
  void requestedUnauthorizedKb_returnsEmptyResults() {
    SearchController controller =
        new SearchController(retrievalService, vectorSearchService, kbAccessGuard, retrievalLogService, org.mockito.Mockito.mock(com.ragforge.storage.ChunkImageResolver.class));
    SearchRequest req = request();
    req.setKbIds(List.of(99L));
    when(kbAccessGuard.filterReadable(List.of(99L))).thenReturn(Set.of());

    Result<?> result = controller.search(req);

    assertThat(result.getCode()).isEqualTo(200);
    assertThat(result.getData()).isNotNull();
    verifyNoInteractions(retrievalService);
  }

  @Test
  void emptyKbIds_autoFillsAllReadableKbIds() {
    SearchController controller =
        new SearchController(retrievalService, vectorSearchService, kbAccessGuard, retrievalLogService, org.mockito.Mockito.mock(com.ragforge.storage.ChunkImageResolver.class));
    SearchRequest req = request();
    when(kbAccessGuard.allReadableKbIds()).thenReturn(new LinkedHashSet<>(List.of(1L, 2L)));
    when(retrievalService.retrieve(eq("java"), any(), eq(List.of(1L, 2L)), any(), any(), any(), eq(8), eq(5), any(), eq("text")))
        .thenReturn(new RetrievalOutput(List.of(), 1L, "vector", null, null, 1L, null, null));

    controller.search(req);

    verify(retrievalService).retrieve(eq("java"), any(), eq(List.of(1L, 2L)), any(), any(), any(), eq(8), eq(5), any(), eq("text"));
  }

  @Test
  void noReadableKbIds_returnsEmptyResults() {
    SearchController controller =
        new SearchController(retrievalService, vectorSearchService, kbAccessGuard, retrievalLogService, org.mockito.Mockito.mock(com.ragforge.storage.ChunkImageResolver.class));
    SearchRequest req = request();
    when(kbAccessGuard.allReadableKbIds()).thenReturn(Set.of());

    Result<?> result = controller.search(req);

    assertThat(result.getCode()).isEqualTo(200);
    assertThat(result.getData()).isNotNull();
    verifyNoInteractions(retrievalService);
  }

  private static SearchRequest request() {
    SearchRequest req = new SearchRequest();
    req.setQuery("java");
    return req;
  }
}
