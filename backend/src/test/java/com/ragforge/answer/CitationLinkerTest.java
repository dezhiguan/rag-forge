package com.ragforge.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.ragforge.answer.AnswerModels.Citation;
import com.ragforge.search.SearchResult;
import com.ragforge.storage.ChunkImageResolver;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CitationLinkerTest {

  @Test
  void linksInRangeAndDropsOutOfRangeRefs() {
    CitationLinker linker = new CitationLinker(Mockito.mock(ChunkImageResolver.class));

    List<Citation> citations =
        linker.link("参考 [1][2][3][99]", List.of(hit(1), hit(2), hit(3)));

    assertThat(citations).hasSize(3);
    assertThat(citations).extracting(Citation::getId).containsExactly(1, 2, 3);
  }

  @Test
  void allRetrievedKeepsEveryChunkRegardlessOfCitations() {
    CitationLinker linker = new CitationLinker(Mockito.mock(ChunkImageResolver.class));

    // 全量快照:不看答案引用了什么,所有检索块都保留(按检索顺序编号 1..N),
    // 这样即使应答 LLM 把引用编号写飘了,裁判仍能拿到真实上下文。
    List<Citation> all = linker.allRetrieved(List.of(hit(11), hit(22)));

    assertThat(all).hasSize(2);
    assertThat(all).extracting(Citation::getId).containsExactly(1, 2);
    assertThat(all).extracting(Citation::getChunkId).containsExactly(11L, 22L);
    assertThat(all).extracting(Citation::getTextSnippet).containsExactly("content-11", "content-22");
  }

  @Test
  void allRetrievedEmptyWhenNoChunks() {
    CitationLinker linker = new CitationLinker(Mockito.mock(ChunkImageResolver.class));
    assertThat(linker.allRetrieved(List.of())).isEmpty();
    assertThat(linker.allRetrieved(null)).isEmpty();
  }

  @Test
  void normalizeCitationMarkersClampsOutOfRange() {
    // 只有 1 个检索块,越界编号 [5] 收敛到 [1];有效编号不变;无块或无编号则原样返回。
    assertThat(CitationLinker.normalizeCitationMarkers("定价为 36000 元 [5]。", 1))
        .isEqualTo("定价为 36000 元 [1]。");
    assertThat(CitationLinker.normalizeCitationMarkers("A[5] B[6] C[7]", 1)).isEqualTo("A[1] B[1] C[1]");
    assertThat(CitationLinker.normalizeCitationMarkers("有效 [2] 越界 [9]", 3)).isEqualTo("有效 [2] 越界 [1]");
    assertThat(CitationLinker.normalizeCitationMarkers("成立于 2018 年 [1]。", 1))
        .isEqualTo("成立于 2018 年 [1]。");
    assertThat(CitationLinker.normalizeCitationMarkers("无编号", 1)).isEqualTo("无编号");
    assertThat(CitationLinker.normalizeCitationMarkers("有编号[3]但无块", 0)).isEqualTo("有编号[3]但无块");
  }

  private SearchResult hit(long id) {
    SearchResult result = new SearchResult();
    result.setChunkId(id);
    result.setDocId(id);
    result.setContent("content-" + id);
    result.setChunkModality("TEXT");
    return result;
  }
}
