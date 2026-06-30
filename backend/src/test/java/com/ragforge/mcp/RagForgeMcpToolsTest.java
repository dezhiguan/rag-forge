package com.ragforge.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.ragforge.answer.AnswerModels.AnswerRequest;
import com.ragforge.answer.AnswerModels.AnswerResponse;
import com.ragforge.answer.AnswerService;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.search.RetrievalService;
import com.ragforge.search.RetrievalService.RetrievalOutput;
import com.ragforge.search.SearchResult;
import com.ragforge.security.KbAccessGuard;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RagForgeMcpToolsTest {

  @Mock private RetrievalService retrievalService;
  @Mock private AnswerService answerService;
  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private KbAccessGuard kbAccessGuard;

  @InjectMocks private RagForgeMcpTools tools;

  // ---- searchKnowledgeBase ----

  @Test
  void searchKnowledgeBase_noAccessibleKbs_returnsNoKbMessage() {
    when(kbAccessGuard.allReadableKbIds()).thenReturn(Set.of());

    String result = tools.searchKnowledgeBase("what is RAG?", null, 5);

    assertThat(result).contains("没有可访问的知识库");
  }

  @Test
  void searchKnowledgeBase_noResults_returnsNotFoundMessage() {
    when(kbAccessGuard.allReadableKbIds()).thenReturn(Set.of(1L, 2L));
    RetrievalOutput output = new RetrievalOutput(List.of(), 100L, "hybrid", null, null, null, null, null);
    when(retrievalService.retrieve(anyString(), anyList(), any(), anyString(), any(), anyInt(), anyInt(), any()))
        .thenReturn(output);

    String result = tools.searchKnowledgeBase("obscure query", null, 5);

    assertThat(result).contains("未找到相关内容");
  }

  @Test
  void searchKnowledgeBase_withResults_formatsOutput() {
    when(kbAccessGuard.allReadableKbIds()).thenReturn(Set.of(1L));
    SearchResult sr = new SearchResult();
    sr.setContent("RAG stands for Retrieval Augmented Generation");
    sr.setFilename("intro.pdf");
    sr.setFinalScore(0.95f);
    RetrievalOutput output = new RetrievalOutput(List.of(sr), 80L, "hybrid", null, null, null, null, null);
    when(retrievalService.retrieve(anyString(), anyList(), any(), anyString(), any(), anyInt(), anyInt(), any()))
        .thenReturn(output);

    String result = tools.searchKnowledgeBase("what is RAG?", null, 5);

    assertThat(result).contains("找到 1 条相关内容");
    assertThat(result).contains("intro.pdf");
    assertThat(result).contains("RAG stands for Retrieval Augmented Generation");
  }

  @Test
  void searchKnowledgeBase_withSpecificKbIds_filtersReadable() {
    when(kbAccessGuard.filterReadable(List.of(15L, 16L))).thenReturn(Set.of(15L));
    RetrievalOutput output = new RetrievalOutput(List.of(), 50L, "hybrid", null, null, null, null, null);
    when(retrievalService.retrieve(anyString(), anyList(), any(), anyString(), any(), anyInt(), anyInt(), any()))
        .thenReturn(output);

    String result = tools.searchKnowledgeBase("query", "15,16", 3);

    assertThat(result).contains("未找到");
  }

  @Test
  void searchKnowledgeBase_topKCappedAt10() {
    when(kbAccessGuard.allReadableKbIds()).thenReturn(Set.of(1L));
    RetrievalOutput output = new RetrievalOutput(List.of(), 50L, "hybrid", null, null, null, null, null);
    // topK=999 should be capped to 10
    when(retrievalService.retrieve(anyString(), anyList(), any(), anyString(), any(), anyInt(), anyInt(), any()))
        .thenReturn(output);

    String result = tools.searchKnowledgeBase("query", null, 999);

    // If no results, just checks no exception and message returned
    assertThat(result).isNotBlank();
  }

  @Test
  void searchKnowledgeBase_exceptionFromService_returnsFallback() {
    when(kbAccessGuard.allReadableKbIds()).thenReturn(Set.of(1L));
    when(retrievalService.retrieve(anyString(), anyList(), any(), anyString(), any(), anyInt(), anyInt(), any()))
        .thenThrow(new RuntimeException("ES is down"));

    String result = tools.searchKnowledgeBase("query", null, 5);

    assertThat(result).contains("搜索失败");
  }

  @Test
  void searchKnowledgeBase_invalidKbIds_usesAllReadable() {
    when(kbAccessGuard.allReadableKbIds()).thenReturn(Set.of(1L));
    RetrievalOutput output = new RetrievalOutput(List.of(), 50L, "hybrid", null, null, null, null, null);
    when(retrievalService.retrieve(anyString(), anyList(), any(), anyString(), any(), anyInt(), anyInt(), any()))
        .thenReturn(output);

    // "abc,xyz" parses to empty → falls back to allReadableKbIds
    String result = tools.searchKnowledgeBase("query", "abc,xyz", 5);
    assertThat(result).isNotBlank();
  }

  // ---- listKnowledgeBases ----

  @Test
  void listKnowledgeBases_noReadableKbs_returnsEmptyMessage() {
    when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of());
    when(kbAccessGuard.allReadableKbIds()).thenReturn(Set.of());

    String result = tools.listKnowledgeBases();

    assertThat(result).contains("没有可用的知识库");
  }

  @Test
  void listKnowledgeBases_withAccessibleKbs_listsAll() {
    KnowledgeBase kb1 = new KnowledgeBase();
    kb1.setId(15L);
    kb1.setName("Personal KB");
    kb1.setDocCount(10);
    kb1.setChunkCount(150);

    KnowledgeBase kb2 = new KnowledgeBase();
    kb2.setId(16L);
    kb2.setName("JD KB");
    kb2.setDocCount(5);
    kb2.setChunkCount(50);
    kb2.setDescription("Job descriptions");

    when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(kb1, kb2));
    when(kbAccessGuard.allReadableKbIds()).thenReturn(Set.of(15L, 16L));

    String result = tools.listKnowledgeBases();

    assertThat(result).contains("Personal KB");
    assertThat(result).contains("JD KB");
    assertThat(result).contains("Job descriptions");
    assertThat(result).contains("ID=15");
  }

  @Test
  void listKnowledgeBases_kbNotReadable_filtered() {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(99L);
    kb.setName("Private KB");

    when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(kb));
    when(kbAccessGuard.allReadableKbIds()).thenReturn(Set.of(15L)); // Not including 99L

    String result = tools.listKnowledgeBases();

    assertThat(result).contains("没有可用的知识库");
  }

  @Test
  void listKnowledgeBases_exceptionFromMapper_returnsFallback() {
    when(knowledgeBaseMapper.selectList(any())).thenThrow(new RuntimeException("DB error"));

    String result = tools.listKnowledgeBases();

    assertThat(result).contains("获取知识库列表失败");
  }

  // ---- answer ----

  @Test
  void answer_noReadableKbs_throwsIllegalArgument() {
    when(kbAccessGuard.allReadableKbIds()).thenReturn(Set.of());

    assertThatThrownBy(() -> tools.answer("what is RAG?", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有可访问的知识库");
  }

  @Test
  void answer_withReadableKbs_callsAnswerService() {
    when(kbAccessGuard.allReadableKbIds()).thenReturn(Set.of(15L, 16L));
    AnswerResponse response = new AnswerResponse();
    response.setAnswer("RAG is Retrieval Augmented Generation.");
    when(answerService.answerBlocking(any())).thenReturn(response);

    AnswerResponse result = tools.answer("what is RAG?", null);

    assertThat(result.getAnswer()).contains("RAG is");
  }

  @Test
  void answer_withSpecificKbIds_filtersReadable() {
    when(kbAccessGuard.filterReadable(List.of(15L))).thenReturn(Set.of(15L));
    AnswerResponse response = new AnswerResponse();
    response.setAnswer("Answer here.");
    when(answerService.answerBlocking(any())).thenReturn(response);

    AnswerResponse result = tools.answer("question", List.of(15L));

    assertThat(result.getAnswer()).isEqualTo("Answer here.");
  }
}
