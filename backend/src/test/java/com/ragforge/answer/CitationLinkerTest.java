package com.ragforge.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.ragforge.answer.AnswerModels.Citation;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.search.SearchResult;
import com.ragforge.storage.ObjectStorage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class CitationLinkerTest {

  @Test
  void linksInRangeAndDropsOutOfRangeRefs() {
    CitationLinker linker =
        new CitationLinker(
            Mockito.mock(DocumentMapper.class),
            Mockito.mock(ObjectStorage.class),
            Mockito.mock(JdbcTemplate.class));

    List<Citation> citations =
        linker.link("参考 [1][2][3][99]", List.of(hit(1), hit(2), hit(3)));

    assertThat(citations).hasSize(3);
    assertThat(citations).extracting(Citation::getId).containsExactly(1, 2, 3);
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
