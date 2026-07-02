package com.ragforge.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ragforge.answer.AnswerModels.AnswerRequest;
import com.ragforge.answer.AnswerModels.AnswerResponse;
import com.ragforge.answer.AnswerService;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.search.RetrievalService;
import com.ragforge.search.RetrievalService.RetrievalOutput;
import com.ragforge.search.SearchResult;
import com.ragforge.storage.ChunkImageResolver;
import java.util.Map;
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
  @Mock private DocumentMapper documentMapper;
  @Mock private KbAccessGuard kbAccessGuard;
  @Mock private ChunkImageResolver chunkImageResolver;

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
  void searchKnowledgeBase_imageChunk_appendsPresignedImageUrl() {
    when(kbAccessGuard.allReadableKbIds()).thenReturn(Set.of(1L));
    SearchResult img = new SearchResult();
    img.setChunkId(42L);
    img.setContent("架构图");
    img.setFilename("arch.pdf");
    RetrievalOutput output =
        new RetrievalOutput(List.of(img), 80L, "hybrid", null, null, null, null, null);
    when(retrievalService.retrieve(
            anyString(), anyList(), any(), anyString(), any(), anyInt(), anyInt(), any()))
        .thenReturn(output);
    // 批量解析：按结果中的 chunkId 返回预签名 URL
    when(chunkImageResolver.presignedUrls(anyList()))
        .thenReturn(Map.of(42L, "https://oss.example/presigned/arch.png"));

    String result = tools.searchKnowledgeBase("架构图", null, 5);

    assertThat(result).contains("图片：https://oss.example/presigned/arch.png");
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
  void searchKnowledgeBase_invalidKbIds_returnsParamError() {
    // 含非法 token 的 kbIds 会抛参数错误（不再静默退化为"搜索全部库"，避免越权扩大范围）。
    String result = tools.searchKnowledgeBase("query", "abc,xyz", 5);
    assertThat(result).contains("参数错误");
    verifyNoInteractions(retrievalService);
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
    kb1.setDocCount(0); // 冗余计数器已漂移为 0，应被实时聚合覆盖（BUG-2）
    kb1.setChunkCount(150);

    KnowledgeBase kb2 = new KnowledgeBase();
    kb2.setId(16L);
    kb2.setName("JD KB");
    kb2.setDocCount(5);
    kb2.setChunkCount(50);
    kb2.setDescription("Job descriptions");

    when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(kb1, kb2));
    when(kbAccessGuard.allReadableKbIds()).thenReturn(Set.of(15L, 16L));
    // 实时聚合文档数：kb15 实有 3 篇（覆盖漂移的 0），kb16 实有 7 篇（覆盖冗余计数器 5）
    when(documentMapper.selectMaps(any()))
        .thenReturn(
            List.of(
                Map.of("kb_id", 15L, "cnt", 3L),
                Map.of("kb_id", 16L, "cnt", 7L)));

    String result = tools.listKnowledgeBases();

    assertThat(result).contains("Personal KB");
    assertThat(result).contains("JD KB");
    assertThat(result).contains("Job descriptions");
    assertThat(result).contains("ID=15");
    // 展示的文档数取实时聚合值，而非实体上漂移的 doc_count
    assertThat(result).contains("文档数=3");
    assertThat(result).contains("文档数=7");
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
