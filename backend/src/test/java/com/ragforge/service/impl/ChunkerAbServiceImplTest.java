package com.ragforge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.mapper.EvalDatasetMapper;
import com.ragforge.mapper.EvalQuestionMapper;
import com.ragforge.model.dto.ChunkerAbRequest;
import com.ragforge.model.entity.EvalDataset;
import com.ragforge.model.entity.EvalQuestion;
import com.ragforge.model.vo.ChunkerAbResponse;
import com.ragforge.pipeline.chunker.Chunk;
import com.ragforge.pipeline.chunker.ChunkParams;
import com.ragforge.pipeline.chunker.ChunkerStrategy;
import com.ragforge.pipeline.chunker.CleanedText;
import com.ragforge.pipeline.chunker.DocumentMeta;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

@ExtendWith(MockitoExtension.class)
class ChunkerAbServiceImplTest {

  @Mock private EvalDatasetMapper evalDatasetMapper;
  @Mock private EvalQuestionMapper evalQuestionMapper;
  @Mock private JdbcTemplate jdbcTemplate;

  private ChunkerAbServiceImpl service;

  @BeforeEach
  void setUp() {
    service =
        new ChunkerAbServiceImpl(
            evalDatasetMapper,
            evalQuestionMapper,
            jdbcTemplate,
            new ObjectMapper(),
            List.of(new FixedStrategy("RECURSIVE"), new FixedStrategy("SEMANTIC")));
  }

  @Test
  void run_requiresDatasetId() {
    assertThatThrownBy(() -> service.run(new ChunkerAbRequest()))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(400));
    verifyNoInteractions(evalDatasetMapper, evalQuestionMapper, jdbcTemplate);
  }

  @Test
  void run_missingDataset_throws404() {
    ChunkerAbRequest request = request(66L, List.of("RECURSIVE"));
    when(evalDatasetMapper.selectById(66L)).thenReturn(null);

    assertThatThrownBy(() -> service.run(request))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(404));
  }

  @Test
  void run_emptyQuestions_returnsEmptyResultsWithoutLoadingChunks() {
    ChunkerAbRequest request = request(66L, List.of("RECURSIVE"));
    when(evalDatasetMapper.selectById(66L)).thenReturn(dataset(66L, 99L));
    when(evalQuestionMapper.selectList(any())).thenReturn(List.of());

    ChunkerAbResponse response = service.run(request);

    assertThat(response.getResults()).isEmpty();
    verifyNoInteractions(jdbcTemplate);
  }

  @Test
  void run_evaluatesRequestedStrategiesAndSkipsUnknownNames() {
    ChunkerAbRequest request = request(66L, List.of(" recursive ", "missing"));
    EvalQuestion question = question(1L, "alpha question", "[1001]");
    when(evalDatasetMapper.selectById(66L)).thenReturn(dataset(66L, 99L));
    when(evalQuestionMapper.selectList(any())).thenReturn(List.of(question));
    when(jdbcTemplate.query(startsWith("SELECT doc_id"), anyResultSetExtractor(), eq(99L)))
        .thenReturn(Map.of(20L, "irrelevant beta chunk", 10L, "alpha expected answer chunk"));
    when(jdbcTemplate.query(contains("WHERE id IN"), anyResultSetExtractor(), any(Object[].class)))
        .thenReturn(Map.of(1001L, "alpha expected answer chunk"));

    ChunkerAbResponse response = service.run(request);

    assertThat(response.getResults()).hasSize(1);
    ChunkerAbResponse.ResultItem item = response.getResults().getFirst();
    assertThat(item.getStrategy()).isEqualTo("RECURSIVE");
    assertThat(item.getTop1()).isEqualTo(1.0);
    assertThat(item.getMrr()).isEqualTo(1.0);
    assertThat(item.getTotalChunks()).isEqualTo(4);
    assertThat(item.getAvgChunkLen()).isGreaterThan(0);
  }

  @Test
  void run_usesDefaultStrategiesAndTextSnippetsBeforeExpectedIds() {
    ChunkerAbRequest request = request(66L, null);
    EvalQuestion question = question(1L, "needle", "[1001]");
    question.setExpectedTextSnippets("[\"needle answer\"]");
    when(evalDatasetMapper.selectById(66L)).thenReturn(dataset(66L, 99L));
    when(evalQuestionMapper.selectList(any())).thenReturn(List.of(question));
    when(jdbcTemplate.query(startsWith("SELECT doc_id"), anyResultSetExtractor(), eq(99L)))
        .thenReturn(Map.of(10L, "needle answer", 20L, "other content"));
    when(jdbcTemplate.query(contains("WHERE id IN"), anyResultSetExtractor(), any(Object[].class)))
        .thenReturn(Map.of(1001L, "unused expected id text"));

    ChunkerAbResponse response = service.run(request);

    assertThat(response.getResults())
        .extracting(ChunkerAbResponse.ResultItem::getStrategy)
        .containsExactly("RECURSIVE", "SEMANTIC");
    assertThat(response.getResults()).allSatisfy(item -> assertThat(item.getTop1()).isEqualTo(1.0));
  }

  @SuppressWarnings("unchecked")
  private static ResultSetExtractor<Map<Long, String>> anyResultSetExtractor() {
    return any(ResultSetExtractor.class);
  }

  private static ChunkerAbRequest request(Long datasetId, List<String> strategies) {
    ChunkerAbRequest request = new ChunkerAbRequest();
    request.setEvalDatasetId(datasetId);
    request.setStrategies(strategies);
    request.setParams(new ChunkParams());
    return request;
  }

  private static EvalDataset dataset(Long id, Long kbId) {
    EvalDataset dataset = new EvalDataset();
    dataset.setId(id);
    dataset.setKbId(kbId);
    return dataset;
  }

  private static EvalQuestion question(Long id, String question, String expectedDocIds) {
    EvalQuestion evalQuestion = new EvalQuestion();
    evalQuestion.setId(id);
    evalQuestion.setDatasetId(66L);
    evalQuestion.setQuestion(question);
    evalQuestion.setExpectedDocIds(expectedDocIds);
    return evalQuestion;
  }

  private static class FixedStrategy implements ChunkerStrategy {
    private final String name;

    FixedStrategy(String name) {
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public boolean supports(DocumentMeta meta) {
      return meta.getTextLength() > 0;
    }

    @Override
    public List<Chunk> split(CleanedText text, ChunkParams params) {
      String content = text.getText();
      return List.of(new Chunk(0, content, content.length()), new Chunk(1, "tail", 4));
    }
  }
}
