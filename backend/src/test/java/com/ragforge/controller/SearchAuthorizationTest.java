package com.ragforge.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.common.BizException;
import com.ragforge.model.dto.SearchRequest;
import com.ragforge.search.RetrievalService;
import com.ragforge.search.RetrievalService.RetrievalOutput;
import com.ragforge.security.KbAccessGuard;
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
  @Mock private KbAccessGuard kbAccessGuard;

  @Test
  void requestedUnauthorizedKb_returnsKbAccessDenied() {
    SearchController controller = new SearchController(retrievalService, kbAccessGuard);
    SearchRequest req = request();
    req.setKbIds(List.of(99L));
    when(kbAccessGuard.filterReadable(List.of(99L))).thenReturn(Set.of());

    assertThatThrownBy(() -> controller.search(req))
        .isInstanceOf(BizException.class)
        .hasMessage("KB_ACCESS_DENIED");
  }

  @Test
  void emptyKbIds_autoFillsAllReadableKbIds() {
    SearchController controller = new SearchController(retrievalService, kbAccessGuard);
    SearchRequest req = request();
    when(kbAccessGuard.allReadableKbIds()).thenReturn(new LinkedHashSet<>(List.of(1L, 2L)));
    when(retrievalService.retrieve(eq("java"), eq(List.of(1L, 2L)), any(), any(), any(), eq(8), eq(5), any()))
        .thenReturn(new RetrievalOutput(List.of(), 1L, "vector", null, null, 1L, null, null));

    controller.search(req);

    verify(retrievalService).retrieve(eq("java"), eq(List.of(1L, 2L)), any(), any(), any(), eq(8), eq(5), any());
  }

  @Test
  void noReadableKbIds_returnsKbAccessDenied() {
    SearchController controller = new SearchController(retrievalService, kbAccessGuard);
    SearchRequest req = request();
    when(kbAccessGuard.allReadableKbIds()).thenReturn(Set.of());

    assertThatThrownBy(() -> controller.search(req))
        .isInstanceOf(BizException.class)
        .hasMessage("KB_ACCESS_DENIED");
  }

  private static SearchRequest request() {
    SearchRequest req = new SearchRequest();
    req.setQuery("java");
    return req;
  }
}
