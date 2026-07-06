package com.ragforge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.common.PageResult;
import com.ragforge.config.EvalProperties;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.EvalDatasetMapper;
import com.ragforge.mapper.EvalExperimentMapper;
import com.ragforge.mapper.EvalQuestionMapper;
import com.ragforge.mapper.EvalResultMapper;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.model.entity.EvalDataset;
import com.ragforge.model.entity.EvalExperiment;
import com.ragforge.model.entity.EvalQuestion;
import com.ragforge.model.entity.EvalResult;
import com.ragforge.search.RetrievalService;
import com.ragforge.search.RetrievalService.RetrievalOutput;
import com.ragforge.search.SearchResult;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class EvalExperimentServiceImplTest {

  @Mock private EvalExperimentMapper evalExperimentMapper;
  @Mock private EvalResultMapper evalResultMapper;
  @Mock private EvalQuestionMapper evalQuestionMapper;
  @Mock private EvalDatasetMapper evalDatasetMapper;
  @Mock private DocumentChunkMapper documentChunkMapper;
  @Mock private RetrievalService retrievalService;
  @Mock private JdbcTemplate jdbcTemplate;
  @Mock private EvalProperties evalProperties;

  @InjectMocks private EvalExperimentServiceImpl evalExperimentService;

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    ReflectionTestUtils.setField(evalExperimentService, "objectMapper", objectMapper);
    ReflectionTestUtils.setField(evalExperimentService, "evalExperimentExecutor", (Executor) Runnable::run);
    ReflectionTestUtils.setField(evalExperimentService, "self", evalExperimentService);
    lenient().when(evalProperties.getQuestionTimeoutMs()).thenReturn(5000L);
  }

  @Test
  void runExperiment_completesWithMetrics() throws Exception {
    EvalDataset dataset = dataset(1L, 10L, "ds-1");
    EvalQuestion q1 = question(1L, 1L, "什么是 RAG？", "[101]");
    EvalQuestion q2 = question(2L, 1L, "如何检索？", "[202,203]");

    when(evalDatasetMapper.selectById(1L)).thenReturn(dataset);
    when(evalQuestionMapper.selectList(any())).thenReturn(List.of(q1, q2));
    doAnswer(
            inv -> {
              EvalExperiment exp = inv.getArgument(0);
              exp.setId(100L);
              return 1;
            })
        .when(evalExperimentMapper)
        .insert(any(EvalExperiment.class));
    when(retrievalService.retrieve(anyString(), anyList(), any(), anyString(), any(), anyInt(), anyInt()))
        .thenAnswer(
            inv -> {
              String query = inv.getArgument(0);
              RetrievalOutput output =
                  new RetrievalOutput(
                      query.contains("RAG")
                          ? List.of(searchResult(101L, 0.9, 0.0))
                          : List.of(
                              searchResult(999L, 0.1, 0.0),
                              searchResult(888L, 0.2, 0.0),
                              searchResult(777L, 0.3, 0.0),
                              searchResult(202L, 0.4, 0.0)),
                      5L,
                      "full",
                      null,
                      null,
                      null,
                      null,
                      null);
              return output;
            });
    when(evalExperimentMapper.selectById(100L)).thenReturn(completedExperiment(100L, 1L));
    when(evalResultMapper.selectList(any())).thenReturn(storedResults());
    when(evalQuestionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(q1, q2));
    when(documentChunkMapper.selectBatchIds(anyCollection()))
        .thenReturn(
            List.of(
                chunk(101L, "chunk-101"),
                chunk(202L, "chunk-202"),
                chunk(203L, "chunk-203")));

    var vo = evalExperimentService.runExperiment(1L, "full", 0.6, 8);

    assertThat(vo.getId()).isEqualTo(100L);
    assertThat(vo.getStrategy()).isEqualTo("full");
    assertThat(vo.getTotalQuestions()).isEqualTo(2);
    verify(evalExperimentMapper).updateById(any(EvalExperiment.class));
    verify(jdbcTemplate).batchUpdate(anyString(), anyList(), eq(100), any());
  }

  @Test
  void runExperiment_persistenceFails_marksFailed_withFriendlyMessage() {
    EvalDataset dataset = dataset(1L, 10L, "ds-1");
    EvalQuestion q1 = question(1L, 1L, "什么是 RAG？", "[101]");
    when(evalDatasetMapper.selectById(1L)).thenReturn(dataset);
    when(evalQuestionMapper.selectList(any())).thenReturn(List.of(q1));
    doAnswer(
            inv -> {
              ((EvalExperiment) inv.getArgument(0)).setId(200L);
              return 1;
            })
        .when(evalExperimentMapper)
        .insert(any(EvalExperiment.class));
    when(retrievalService.retrieve(anyString(), anyList(), any(), anyString(), any(), anyInt(), anyInt()))
        .thenReturn(
            new RetrievalOutput(
                List.of(searchResult(101L, 0.9, 0.0)), 5L, "vector", null, null, null, null, null));
    // 检索成功、但结果落库(批插)失败 → 触发外层 catch。
    when(jdbcTemplate.batchUpdate(anyString(), anyList(), anyInt(), any()))
        .thenThrow(new RuntimeException("内部错误 secret-detail-xyz"));

    assertThatThrownBy(() -> evalExperimentService.runExperiment(1L, "vector", null, 8))
        .isInstanceOfSatisfying(
            BizException.class,
            ex -> {
              assertThat(ex.getCode()).isEqualTo(500);
              assertThat(ex.getMessage()).isEqualTo("评测实验运行失败，请稍后重试"); // 友好、不泄露
              assertThat(ex.getMessage()).doesNotContain("secret");
            });

    // markAsFailed 生效:实验置 failed 落库(证明 experiment 已提交、REQUIRES_NEW 能看到该行)。
    ArgumentCaptor<EvalExperiment> captor = ArgumentCaptor.forClass(EvalExperiment.class);
    verify(evalExperimentMapper).updateById(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo("failed");
  }

  @Test
  void runExperiment_withoutQuestions_throws400() {
    EvalDataset dataset = dataset(1L, 10L, "ds-1");
    when(evalDatasetMapper.selectById(1L)).thenReturn(dataset);
    when(evalQuestionMapper.selectList(any())).thenReturn(List.of());

    assertThatThrownBy(() -> evalExperimentService.runExperiment(1L, "vector", null, null))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("暂无评测问题");
  }

  @Test
  void runExperiment_missingDataset_throws404() {
    when(evalDatasetMapper.selectById(404L)).thenReturn(null);

    assertThatThrownBy(() -> evalExperimentService.runExperiment(404L, "vector", null, null))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(404));
  }

  @Test
  void runExperiment_searchFailure_stillCompletesWithFailedQuestion() {
    EvalDataset dataset = dataset(1L, 10L, "ds-1");
    EvalQuestion q1 = question(1L, 1L, "q", "[101]");
    when(evalDatasetMapper.selectById(1L)).thenReturn(dataset);
    when(evalQuestionMapper.selectList(any())).thenReturn(List.of(q1));
    doAnswer(
            inv -> {
              EvalExperiment exp = inv.getArgument(0);
              exp.setId(200L);
              return 1;
            })
        .when(evalExperimentMapper)
        .insert(any(EvalExperiment.class));
    when(retrievalService.retrieve(anyString(), anyList(), any(), anyString(), any(), anyInt(), anyInt()))
        .thenThrow(new RuntimeException("search down"));
    EvalExperiment completed = completedExperiment(200L, 1L);
    completed.setTop1HitCount(0);
    completed.setTop3HitCount(0);
    when(evalExperimentMapper.selectById(200L)).thenReturn(completed);
    when(evalResultMapper.selectList(any()))
        .thenReturn(List.of(resultRow(200L, 1L, false, null, "[]", "检索异常")));
    when(evalQuestionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(q1));
    when(documentChunkMapper.selectBatchIds(anyCollection())).thenReturn(List.of(chunk(101L, "c")));

    var vo = evalExperimentService.runExperiment(1L, "hybrid", 0.5, 5);

    assertThat(vo.getTop1HitCount()).isZero();
    verify(evalExperimentMapper).updateById(any(EvalExperiment.class));
  }

  @Test
  void listRecent_returnsSummaries() {
    EvalExperiment exp = completedExperiment(11L, 1L);
    when(evalExperimentMapper.selectList(any())).thenReturn(List.of(exp));
    when(evalDatasetMapper.selectList(any())).thenReturn(List.of(dataset(1L, 10L, "ds-1")));

    var list = evalExperimentService.listRecent();

    assertThat(list).hasSize(1);
    assertThat(list.get(0).getDatasetName()).isEqualTo("ds-1");
  }

  @Test
  void list_paged_returnsPageResultWithTotal() {
    EvalExperiment exp = completedExperiment(11L, 1L);
    Page<EvalExperiment> page = new Page<>(1, 10);
    page.setRecords(List.of(exp));
    page.setTotal(1);
    when(evalExperimentMapper.selectPage(any(), any())).thenReturn(page);
    when(evalDatasetMapper.selectList(any())).thenReturn(List.of(dataset(1L, 10L, "ds-1")));

    PageResult<?> result = evalExperimentService.list(1, 10, null);

    assertThat(result.getTotal()).isEqualTo(1);
    assertThat(result.getList()).hasSize(1);
  }

  @Test
  void list_filterByDatasetName_noMatch_returnsEmptyWithoutQueryingExperiments() {
    // 关键词无匹配数据集 → 直接返回空页，不查实验表。
    when(evalDatasetMapper.selectList(any())).thenReturn(List.of());

    PageResult<?> result = evalExperimentService.list(1, 10, "不存在的数据集");

    assertThat(result.getTotal()).isZero();
    assertThat(result.getList()).isEmpty();
    verify(evalExperimentMapper, org.mockito.Mockito.never()).selectPage(any(), any());
  }

  @Test
  void getDetail_buildsFailureSamplesAndSuggestions() throws Exception {
    EvalExperiment exp = completedExperiment(30L, 1L);
    exp.setStrategy("keyword");
    EvalQuestion q1 = question(1L, 1L, "miss", "[]");
    EvalQuestion q2 = question(2L, 1L, "rank", "[501]");
    EvalResult r1 = resultRow(30L, 1L, false, null, "[]", "标注缺失");
    EvalResult r2 = resultRow(30L, 2L, false, null, "[999,998,997,501]", "排序不足");

    when(evalExperimentMapper.selectById(30L)).thenReturn(exp);
    when(evalDatasetMapper.selectById(1L)).thenReturn(dataset(1L, 10L, "ds-1"));
    when(evalResultMapper.selectList(any())).thenReturn(List.of(r1, r2));
    when(evalQuestionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(q1, q2));
    when(documentChunkMapper.selectBatchIds(anyCollection())).thenReturn(List.of(chunk(501L, "expected")));

    var detail = evalExperimentService.getDetail(30L);

    assertThat(detail.getResults()).hasSize(2);
    assertThat(detail.getFailureSamples()).hasSize(2);
    assertThat(detail.getResults().get(0).getSuggestion()).contains("标准 Chunk");
    assertThat(detail.getResults().get(1).getSuggestion()).contains("hybrid");
  }

  @Test
  void delete_removesExperimentAndResults() {
    EvalExperiment exp = completedExperiment(40L, 1L);
    when(evalExperimentMapper.selectById(40L)).thenReturn(exp);

    evalExperimentService.delete(40L);

    verify(evalResultMapper).delete(any());
    verify(evalExperimentMapper).deleteById(40L);
  }

  @Test
  void delete_missingExperiment_throws404() {
    when(evalExperimentMapper.selectById(404L)).thenReturn(null);

    assertThatThrownBy(() -> evalExperimentService.delete(404L))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(404));
  }

  @Test
  void markAsFailed_updatesStatusInNewTransaction() {
    EvalExperiment exp = completedExperiment(50L, 1L);
    exp.setStatus("running");

    evalExperimentService.markAsFailed(exp);

    assertThat(exp.getStatus()).isEqualTo("failed");
    verify(evalExperimentMapper).updateById(exp);
  }

  @Test
  void runExperiment_normalizesStrategyAliases() throws Exception {
    EvalDataset dataset = dataset(1L, 10L, "ds-1");
    EvalQuestion q1 = question(1L, 1L, "q", "[101]");
    when(evalDatasetMapper.selectById(1L)).thenReturn(dataset);
    when(evalQuestionMapper.selectList(any())).thenReturn(List.of(q1));
    doAnswer(
            inv -> {
              EvalExperiment exp = inv.getArgument(0);
              exp.setId(60L);
              return 1;
            })
        .when(evalExperimentMapper)
        .insert(any(EvalExperiment.class));
    when(retrievalService.retrieve(anyString(), anyList(), any(), eq("rewrite"), any(), anyInt(), anyInt()))
        .thenReturn(outputWith(searchResult(101L, 0.5, 0.0)));
    when(evalExperimentMapper.selectById(60L)).thenAnswer(inv -> {
      EvalExperiment exp = completedExperiment(60L, 1L);
      exp.setStrategy("rewrite");
      return exp;
    });
    when(evalResultMapper.selectList(any())).thenReturn(List.of(resultRow(60L, 1L, true, 1, "[101]", null)));
    when(evalQuestionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(q1));
    when(documentChunkMapper.selectBatchIds(anyCollection())).thenReturn(List.of(chunk(101L, "c")));

    var vo = evalExperimentService.runExperiment(1L, "REWRITE", null, null);

    assertThat(vo.getStrategy()).isEqualTo("rewrite");
  }

  @Test
  void runExperiment_matchesExpectedTextSnippetsWithNormalizedContent() throws Exception {
    EvalDataset dataset = dataset(1L, 10L, "ds-1");
    EvalQuestion q1 = questionWithSnippets(1L, 1L, "q", "[]", "[\"Alpha Beta\"]");
    when(evalDatasetMapper.selectById(1L)).thenReturn(dataset);
    when(evalQuestionMapper.selectList(any())).thenReturn(List.of(q1));
    doAnswer(
            inv -> {
              EvalExperiment exp = inv.getArgument(0);
              exp.setId(70L);
              return 1;
            })
        .when(evalExperimentMapper)
        .insert(any(EvalExperiment.class));
    SearchResult hit = searchResult(901L, 0.5, 0.0);
    hit.setContent("alpha   beta content");
    when(retrievalService.retrieve(anyString(), anyList(), any(), anyString(), any(), anyInt(), anyInt()))
        .thenReturn(outputWith(hit));
    doAnswer(
            inv -> {
              List<EvalResult> rows = inv.getArgument(1);
              assertThat(rows).hasSize(1);
              assertThat(rows.get(0).getHit()).isTrue();
              assertThat(rows.get(0).getHitAt()).isEqualTo(1);
              assertThat(rows.get(0).getFailureReason()).isNull();
              return new int[][] {{1}};
            })
        .when(jdbcTemplate)
        .batchUpdate(anyString(), anyList(), eq(100), any());
    when(evalExperimentMapper.selectById(70L)).thenReturn(completedExperiment(70L, 1L));
    when(evalResultMapper.selectList(any())).thenReturn(List.of(resultRow(70L, 1L, true, 1, "[901]", null)));
    when(evalQuestionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(q1));
    when(documentChunkMapper.selectBatchIds(anyCollection())).thenReturn(List.of(chunk(901L, "alpha beta content")));

    var vo = evalExperimentService.runExperiment(1L, "vector", null, null);

    assertThat(vo.getResults().get(0).getExpectedTextSnippets()).containsExactly("Alpha Beta");
    assertThat(vo.getResults().get(0).getExpectedTextMatches().get(0).getMatched()).isTrue();
  }

  @Test
  void getDetail_buildsExpectedTextMatchDetails() {
    EvalExperiment exp = completedExperiment(80L, 1L);
    EvalQuestion q1 = questionWithSnippets(1L, 1L, "q", "[]", "[\"AlphaBeta\"]");
    EvalResult r1 = resultRow(80L, 1L, true, 1, "[301]", null);

    when(evalExperimentMapper.selectById(80L)).thenReturn(exp);
    when(evalDatasetMapper.selectById(1L)).thenReturn(dataset(1L, 10L, "ds-1"));
    when(evalResultMapper.selectList(any())).thenReturn(List.of(r1));
    when(evalQuestionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(q1));
    when(documentChunkMapper.selectBatchIds(anyCollection())).thenReturn(List.of(chunk(301L, "alpha beta text")));

    var detail = evalExperimentService.getDetail(80L);

    var result = detail.getResults().get(0);
    assertThat(result.getExpectedTextSnippets()).containsExactly("AlphaBeta");
    assertThat(result.getExpectedTextMatches()).hasSize(1);
    assertThat(result.getExpectedTextMatches().get(0).getMatchedChunkId()).isEqualTo(301L);
  }

  private static EvalDataset dataset(long id, long kbId, String name) {
    EvalDataset dataset = new EvalDataset();
    dataset.setId(id);
    dataset.setKbId(kbId);
    dataset.setName(name);
    return dataset;
  }

  private static EvalQuestion question(long id, long datasetId, String text, String expectedJson) {
    return questionWithSnippets(id, datasetId, text, expectedJson, null);
  }

  private static EvalQuestion questionWithSnippets(
      long id, long datasetId, String text, String expectedJson, String expectedTextSnippetsJson) {
    EvalQuestion question = new EvalQuestion();
    question.setId(id);
    question.setDatasetId(datasetId);
    question.setQuestion(text);
    question.setExpectedDocIds(expectedJson);
    question.setExpectedTextSnippets(expectedTextSnippetsJson);
    return question;
  }

  private static EvalExperiment completedExperiment(long id, long datasetId) {
    EvalExperiment exp = new EvalExperiment();
    exp.setId(id);
    exp.setDatasetId(datasetId);
    exp.setStrategy("full");
    exp.setTotalQuestions(2);
    exp.setTop1HitCount(1);
    exp.setTop3HitCount(1);
    exp.setTop1HitRate(new BigDecimal("0.5000"));
    exp.setTop3HitRate(new BigDecimal("0.5000"));
    exp.setMrr(new BigDecimal("0.7500"));
    exp.setAvgLatencyMs(10);
    exp.setStatus("completed");
    exp.setCreatedAt(LocalDateTime.now());
    return exp;
  }

  private static SearchResult searchResult(long chunkId, double vectorScore, double bm25Score) {
    SearchResult result = new SearchResult();
    result.setChunkId(chunkId);
    result.setDocId(chunkId);
    result.setContent("content-" + chunkId);
    result.setVectorScore(vectorScore);
    result.setBm25Score(bm25Score);
    return result;
  }

  private static RetrievalOutput outputWith(SearchResult result) {
    return new RetrievalOutput(List.of(result), 5L, "rewrite", null, null, null, null, null);
  }

  private static EvalResult resultRow(
      long experimentId,
      long questionId,
      boolean hit,
      Integer hitAt,
      String recalledJson,
      String failureReason) {
    EvalResult row = new EvalResult();
    row.setExperimentId(experimentId);
    row.setQuestionId(questionId);
    row.setHit(hit);
    row.setHitAt(hitAt);
    row.setRecalledChunkIds(recalledJson);
    row.setScore(BigDecimal.ONE);
    row.setLatencyMs(12);
    row.setFailureReason(failureReason);
    return row;
  }

  private static List<EvalResult> storedResults() {
    return List.of(
        resultRow(100L, 1L, true, 1, "[101]", null),
        resultRow(100L, 2L, false, null, "[999,888,777,202]", "排序不足"));
  }

  private static DocumentChunk chunk(long id, String content) {
    DocumentChunk chunk = new DocumentChunk();
    chunk.setId(id);
    chunk.setDocId(id + 1000);
    chunk.setChunkIndex(0);
    chunk.setContent(content);
    chunk.setTokenCount(10);
    return chunk;
  }
}
