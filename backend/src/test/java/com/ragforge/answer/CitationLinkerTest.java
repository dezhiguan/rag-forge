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

  @Test
  void reanchorMovesDriftedMarkerToSupportingChunk() {
    // 复现线上漂移:答案内容正确(30天滑动续期来自安全规范=块1),但 LLM 把编号写成 [3](块3=检索策略,不含该事实)。
    List<SearchResult> chunks =
        List.of(
            chunk(20393, "RAGForge 安全与权限规范。access token 有效期为 15 分钟。refresh token 采用旋转机制，有效期 7 天；开启记住我后为 30 天滑动续期。"),
            chunk(20395, "常见问题 FAQ。向量检索为什么走顺序扫描。因为文档向量是 2560 维。"),
            chunk(20396, "检索策略指标。vector 默认并发 48；hybrid 并发 20；full 调用重排。"));
    String out =
        CitationLinker.reanchorCitationMarkers("开启记住我后，refresh token 的有效期为 30 天滑动续期 [3]。", chunks);
    assertThat(out).isEqualTo("开启记住我后，refresh token 的有效期为 30 天滑动续期 [1]。");
  }

  @Test
  void reanchorKeepsCorrectMarkersUntouched() {
    // 编号本就正确(该句事实确实在块2)→ 不动，避免过度纠正。
    List<SearchResult> chunks =
        List.of(
            chunk(1, "部署形态：k3s 单节点集群，命名空间 ragforge。"),
            chunk(2, "向量维度统一为 2560 维，超过 pgvector 2000 维上限，走顺序扫描。"));
    String out = CitationLinker.reanchorCitationMarkers("文档向量维度是 2560 维 [2]。", chunks);
    assertThat(out).isEqualTo("文档向量维度是 2560 维 [2]。");
  }

  @Test
  void reanchorClampsOutOfRangeWithoutMatch() {
    // 越界且无块支撑 → 收敛到 [1]（沿用旧规范化行为）。
    List<SearchResult> chunks = List.of(chunk(1, "与问题完全无关的内容 foobar baz。"));
    assertThat(CitationLinker.reanchorCitationMarkers("某个事实 [5]。", chunks))
        .isEqualTo("某个事实 [1]。");
  }

  private SearchResult chunk(long id, String content) {
    SearchResult result = new SearchResult();
    result.setChunkId(id);
    result.setDocId(id);
    result.setContent(content);
    result.setChunkModality("TEXT");
    return result;
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
