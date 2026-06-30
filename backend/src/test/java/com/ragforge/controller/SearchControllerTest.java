package com.ragforge.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.ragforge.common.GlobalExceptionHandler;
import com.ragforge.model.dto.SearchRequest;
import com.ragforge.search.RetrievalService;
import com.ragforge.security.KbAccessGuard;
import com.ragforge.service.RetrievalLogService;
import com.ragforge.search.RetrievalService.RetrievalOutput;
import com.ragforge.search.SearchResult;
import com.ragforge.search.VectorSearchService;
import com.ragforge.storage.ChunkImageResolver;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

  @Mock private RetrievalService retrievalService;
  @Mock private VectorSearchService vectorSearchService;
  @Mock private KbAccessGuard kbAccessGuard;
  @Mock private RetrievalLogService retrievalLogService;
  @Mock private ChunkImageResolver chunkImageResolver;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        standaloneSetup(
                new SearchController(
                    retrievalService, vectorSearchService, kbAccessGuard, retrievalLogService,
                    chunkImageResolver))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .setValidator(validator)
            .build();
  }

  @Test
  void blankQuery_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void search_delegatesToRetrievalServiceAndMapsResponse() throws Exception {
    SearchResult hit = new SearchResult();
    hit.setChunkId(42L);
    hit.setContent("Java backend");
    hit.setFinalScore(0.88);

    RetrievalOutput output =
        new RetrievalOutput(
            List.of(hit),
            120L,
            "hybrid",
            null,
            null,
            50L,
            70L,
            null);

    when(kbAccessGuard.filterReadable(List.of(17L))).thenReturn(Set.of(17L));

    when(retrievalService.retrieve(
            eq("Java"),
            isNull(),
            eq(List.of(17L)),
            isNull(),
            eq("hybrid"),
            eq(0.6),
            eq(5),
            eq(3),
            isNull(),
            eq("text")))
        .thenReturn(output);

    mockMvc
        .perform(
            post("/api/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "query": "Java",
                      "kbIds": [17],
                      "strategy": "hybrid",
                      "vectorWeight": 0.6,
                      "topK": 5,
                      "rerankTopN": 3
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.strategy").value("hybrid"))
        .andExpect(jsonPath("$.data.latencyMs").value(120))
        .andExpect(jsonPath("$.data.results[0].chunkId").value(42))
        .andExpect(jsonPath("$.data.results[0].finalScore").value(0.88));

    verify(retrievalService)
        .retrieve(
            eq("Java"),
            isNull(),
            eq(List.of(17L)),
            isNull(),
            eq("hybrid"),
            eq(0.6),
            eq(5),
            eq(3),
            isNull(),
            eq("text"));
    verify(retrievalLogService)
        .logAsync(
            eq("Java"),
            eq("hybrid"),
            eq(List.of(17L)),
            isNull(),
            eq(5),
            eq(1),
            eq(120L),
            eq(List.of(hit)),
            isNull(),
            isNull(),
            isNull());
  }

  @Test
  void search_noAccessibleKbs_returnsEmptyResult() throws Exception {
    when(kbAccessGuard.filterReadable(List.of(99L))).thenReturn(Set.of());

    mockMvc
        .perform(
            post("/api/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"Java\",\"kbIds\":[99]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.results").isArray())
        .andExpect(jsonPath("$.data.results").isEmpty());
  }

  @Test
  void searchByImage_blankImage_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/search/by-image")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryImageBase64\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void searchByImage_noAccessibleKbs_returnsEmpty() throws Exception {
    when(kbAccessGuard.allReadableKbIds()).thenReturn(Set.of());

    mockMvc
        .perform(
            post("/api/v1/search/by-image")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryImageBase64\":\"aGVsbG8=\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.results").isEmpty());
  }

  @Test
  void searchByImage_withResults_mapsResponse() throws Exception {
    SearchResult hit = new SearchResult();
    hit.setChunkId(55L);
    hit.setContent("photo content");
    hit.setChunkModality("IMAGE");

    when(kbAccessGuard.allReadableKbIds()).thenReturn(Set.of(17L));
    when(chunkImageResolver.presignedUrls(List.of(55L))).thenReturn(java.util.Map.of());
    when(vectorSearchService.searchImage(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of(hit));

    mockMvc
        .perform(
            post("/api/v1/search/by-image")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryImageBase64\":\"aGVsbG8=\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.results[0].chunkId").value(55))
        .andExpect(jsonPath("$.data.strategy").value("image-vector"));
  }

  @Test
  void search_passesChunkTypeFilter() throws Exception {
    RetrievalOutput output =
        new RetrievalOutput(List.of(), 10L, "vector", null, null, 10L, null, null);

    when(kbAccessGuard.allReadableKbIds()).thenReturn(Set.of(17L));

    when(retrievalService.retrieve(
            eq("spring"),
            isNull(),
            eq(List.of(17L)),
            isNull(),
            eq("vector"),
            eq(0.55),
            eq(8),
            eq(5),
            org.mockito.ArgumentMatchers.any(SearchRequest.SearchFilter.class),
            eq("text")))
        .thenReturn(output);

    mockMvc
        .perform(
            post("/api/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "query": "spring",
                      "filter": { "chunkType": ["JD", "RESUME"] }
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.strategy").value("vector"));

    verify(retrievalService)
        .retrieve(
            eq("spring"),
            isNull(),
            eq(List.of(17L)),
            isNull(),
            eq("vector"),
            eq(0.55),
            eq(8),
            eq(5),
            org.mockito.ArgumentMatchers.argThat(
                filter ->
                    filter != null
                        && filter.getChunkType() != null
                        && filter.getChunkType().containsAll(List.of("JD", "RESUME"))),
            eq("text"));
  }
}
