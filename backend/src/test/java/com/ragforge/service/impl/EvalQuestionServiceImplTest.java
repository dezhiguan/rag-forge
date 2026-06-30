package com.ragforge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.mapper.EvalDatasetMapper;
import com.ragforge.mapper.EvalQuestionMapper;
import com.ragforge.model.dto.CreateEvalQuestionDTO;
import com.ragforge.model.dto.SaveQuestionFromSearchDTO;
import com.ragforge.model.entity.EvalDataset;
import com.ragforge.model.entity.EvalQuestion;
import com.ragforge.service.EvalDatasetService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvalQuestionServiceImplTest {

  @Mock private EvalQuestionMapper evalQuestionMapper;
  @Mock private EvalDatasetMapper evalDatasetMapper;
  @Mock private EvalDatasetService evalDatasetService;

  @InjectMocks private EvalQuestionServiceImpl evalQuestionService;

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    org.springframework.test.util.ReflectionTestUtils.setField(
        evalQuestionService, "objectMapper", objectMapper);
  }

  @Test
  void create_persistsQuestionAndIncrementsCount() {
    CreateEvalQuestionDTO dto = new CreateEvalQuestionDTO();
    dto.setQuestion("  what is RAG?  ");
    dto.setExpectedChunkIds(List.of(1L, 2L));
    dto.setExpectedTextSnippets(List.of("  stable text  "));

    EvalDataset dataset = dataset(5L, 2);
    when(evalDatasetMapper.selectById(5L)).thenReturn(dataset);

    var vo = evalQuestionService.create(5L, dto);

    assertThat(vo.getQuestion()).isEqualTo("what is RAG?");
    assertThat(vo.getExpectedChunkIds()).containsExactly(1L, 2L);
    assertThat(vo.getExpectedTextSnippets()).containsExactly("stable text");
    verify(evalQuestionMapper).insert(any(EvalQuestion.class));
    ArgumentCaptor<EvalDataset> captor = ArgumentCaptor.forClass(EvalDataset.class);
    verify(evalDatasetMapper).updateById(captor.capture());
    assertThat(captor.getValue().getQuestionCount()).isEqualTo(3);
  }

  @Test
  void update_persistsExpectedTextSnippets() {
    EvalQuestion question = question(7L, 5L, "q", "[1]");
    when(evalQuestionMapper.selectById(7L)).thenReturn(question);

    CreateEvalQuestionDTO dto = new CreateEvalQuestionDTO();
    dto.setQuestion("updated");
    dto.setExpectedChunkIds(List.of(2L));
    dto.setExpectedTextSnippets(List.of("片段 A", "  ", "片段 B"));

    var vo = evalQuestionService.update(5L, 7L, dto);

    assertThat(vo.getExpectedTextSnippets()).containsExactly("片段 A", "片段 B");
    ArgumentCaptor<EvalQuestion> captor = ArgumentCaptor.forClass(EvalQuestion.class);
    verify(evalQuestionMapper).updateById(captor.capture());
    assertThat(captor.getValue().getExpectedTextSnippets()).isEqualTo("[\"片段 A\",\"片段 B\"]");
  }

  @Test
  void createFromSearch_mapsSelectedChunks() {
    SaveQuestionFromSearchDTO dto = new SaveQuestionFromSearchDTO();
    dto.setQuestion("query");
    dto.setSelectedChunkIds(List.of(9L));

    when(evalDatasetMapper.selectById(5L)).thenReturn(dataset(5L, 0));

    var vo = evalQuestionService.createFromSearch(5L, dto);

    assertThat(vo.getExpectedChunkIds()).containsExactly(9L);
  }

  @Test
  void batchCreate_emptyInputReturnsEmptyWithoutInsert() {
    var result = evalQuestionService.batchCreate(5L, List.of());

    assertThat(result).isEmpty();
    verify(evalDatasetService).requireDataset(5L);
    verify(evalQuestionMapper, never()).insert(any(EvalQuestion.class));
  }

  @Test
  void batchCreate_deduplicatesJudgeTagsAndIncrementsByCreatedSize() {
    CreateEvalQuestionDTO first = new CreateEvalQuestionDTO();
    first.setQuestion("q1");
    first.setExpectedChunkIds(List.of(1L));
    first.setJudgeEnabled(true);
    first.setJudgeTags(List.of(" logic ", "", "logic", "safety"));
    CreateEvalQuestionDTO second = new CreateEvalQuestionDTO();
    second.setQuestion("q2");
    second.setExpectedChunkIds(List.of(2L));
    when(evalDatasetMapper.selectById(5L)).thenReturn(dataset(5L, 1));

    var result = evalQuestionService.batchCreate(5L, List.of(first, second));

    assertThat(result).hasSize(2);
    ArgumentCaptor<EvalQuestion> questionCaptor = ArgumentCaptor.forClass(EvalQuestion.class);
    verify(evalQuestionMapper, org.mockito.Mockito.times(2)).insert(questionCaptor.capture());
    assertThat(questionCaptor.getAllValues().get(0).getJudgeEnabled()).isTrue();
    assertThat(questionCaptor.getAllValues().get(0).getJudgeTags()).containsExactly("logic", "safety");
    ArgumentCaptor<EvalDataset> datasetCaptor = ArgumentCaptor.forClass(EvalDataset.class);
    verify(evalDatasetMapper).updateById(datasetCaptor.capture());
    assertThat(datasetCaptor.getValue().getQuestionCount()).isEqualTo(3);
  }

  @Test
  void update_missingQuestion_throws404() {
    when(evalQuestionMapper.selectById(99L)).thenReturn(null);

    CreateEvalQuestionDTO dto = new CreateEvalQuestionDTO();
    dto.setQuestion("q");
    dto.setExpectedChunkIds(List.of(1L));

    assertThatThrownBy(() -> evalQuestionService.update(5L, 99L, dto))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(404));
  }

  @Test
  void delete_decrementsQuestionCount() {
    EvalQuestion question = question(7L, 5L, "q", "[1]");
    when(evalQuestionMapper.selectById(7L)).thenReturn(question);
    when(evalDatasetMapper.selectById(5L)).thenReturn(dataset(5L, 3));

    evalQuestionService.delete(5L, 7L);

    verify(evalQuestionMapper).deleteById(7L);
    ArgumentCaptor<EvalDataset> captor = ArgumentCaptor.forClass(EvalDataset.class);
    verify(evalDatasetMapper).updateById(captor.capture());
    assertThat(captor.getValue().getQuestionCount()).isEqualTo(2);
  }

  @Test
  void delete_missingQuestion_throws404AndDoesNotDecrement() {
    when(evalQuestionMapper.selectById(8L)).thenReturn(null);

    assertThatThrownBy(() -> evalQuestionService.delete(5L, 8L))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(404));

    verify(evalDatasetMapper, never()).updateById(any(EvalDataset.class));
  }

  @Test
  void delete_neverDecrementsBelowZero() {
    EvalQuestion question = question(7L, 5L, "q", "[1]");
    when(evalQuestionMapper.selectById(7L)).thenReturn(question);
    when(evalDatasetMapper.selectById(5L)).thenReturn(dataset(5L, 0));

    evalQuestionService.delete(5L, 7L);

    ArgumentCaptor<EvalDataset> captor = ArgumentCaptor.forClass(EvalDataset.class);
    verify(evalDatasetMapper).updateById(captor.capture());
    assertThat(captor.getValue().getQuestionCount()).isZero();
  }

  @Test
  void listByDataset_returnsPagedResults() {
    EvalQuestion question = question(1L, 5L, "q", "[1]");
    Page<EvalQuestion> page = new Page<>(1, 10);
    page.setRecords(List.of(question));
    page.setTotal(1);
    when(evalQuestionMapper.selectPage(any(Page.class), any())).thenReturn(page);

    var result = evalQuestionService.listByDataset(5L, 1, 10);

    assertThat(result.getList()).hasSize(1);
    verify(evalDatasetService).requireDataset(5L);
  }

  @Test
  void listByDataset_invalidJsonAndDuplicateTagsFallBackGracefully() {
    EvalQuestion question = question(1L, 5L, "q", "not-json");
    question.setExpectedTextSnippets("not-json");
    question.setJudgeEnabled(null);
    question.setJudgeTags(new String[] {" tag ", "", "tag", "risk"});
    Page<EvalQuestion> page = new Page<>(2, 5);
    page.setRecords(List.of(question));
    page.setTotal(1);
    when(evalQuestionMapper.selectPage(any(Page.class), any())).thenReturn(page);

    var result = evalQuestionService.listByDataset(5L, 2, 5);

    var vo = result.getList().get(0);
    assertThat(vo.getExpectedChunkIds()).isEmpty();
    assertThat(vo.getExpectedTextSnippets()).isEmpty();
    assertThat(vo.getJudgeEnabled()).isFalse();
    assertThat(vo.getJudgeTags()).containsExactly("tag", "risk");
  }

  private static EvalDataset dataset(long id, int questionCount) {
    EvalDataset dataset = new EvalDataset();
    dataset.setId(id);
    dataset.setQuestionCount(questionCount);
    return dataset;
  }

  private static EvalQuestion question(long id, long datasetId, String text, String expectedJson) {
    EvalQuestion question = new EvalQuestion();
    question.setId(id);
    question.setDatasetId(datasetId);
    question.setQuestion(text);
    question.setExpectedDocIds(expectedJson);
    return question;
  }
}
