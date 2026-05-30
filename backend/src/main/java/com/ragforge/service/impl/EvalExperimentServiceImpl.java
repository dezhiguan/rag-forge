package com.ragforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.mapper.EvalDatasetMapper;
import com.ragforge.mapper.EvalExperimentMapper;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.EvalQuestionMapper;
import com.ragforge.mapper.EvalResultMapper;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.model.entity.EvalDataset;
import com.ragforge.model.entity.EvalExperiment;
import com.ragforge.model.entity.EvalQuestion;
import com.ragforge.model.entity.EvalResult;
import com.ragforge.model.vo.ChunkPreviewVO;
import com.ragforge.model.vo.EvalExperimentVO;
import com.ragforge.model.vo.EvalFailureSampleVO;
import com.ragforge.model.vo.EvalResultDetailVO;
import com.ragforge.search.RetrievalService;
import com.ragforge.search.SearchResult;
import com.ragforge.service.EvalExperimentService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class EvalExperimentServiceImpl implements EvalExperimentService {

  private static final TypeReference<List<Long>> LONG_LIST_TYPE = new TypeReference<>() {};

  private final EvalExperimentMapper evalExperimentMapper;
  private final EvalResultMapper evalResultMapper;
  private final EvalQuestionMapper evalQuestionMapper;
  private final EvalDatasetMapper evalDatasetMapper;
  private final DocumentChunkMapper documentChunkMapper;
  private final RetrievalService retrievalService;
  private final ObjectMapper objectMapper;
  private final JdbcTemplate jdbcTemplate;

  @Lazy
  @Autowired
  private EvalExperimentServiceImpl self;

  @Override
  @Transactional
  public EvalExperimentVO runExperiment(Long datasetId, String strategy, Double vectorWeight, Integer topK) {
    EvalDataset dataset = requireDataset(datasetId);
    List<EvalQuestion> questions =
        evalQuestionMapper.selectList(
            new LambdaQueryWrapper<EvalQuestion>()
                .eq(EvalQuestion::getDatasetId, datasetId)
                .orderByAsc(EvalQuestion::getId));
    if (questions.isEmpty()) {
      throw new BizException(400, "数据集下暂无评测问题");
    }

    String normalizedStrategy = normalizeStrategy(strategy);
    int runTopK = topK != null && topK > 0 ? topK : 8;
    double runVectorWeight = vectorWeight != null ? Math.max(0, Math.min(1, vectorWeight)) : 0.55;

    EvalExperiment experiment = new EvalExperiment();
    experiment.setDatasetId(datasetId);
    experiment.setStrategy(normalizedStrategy);
    experiment.setEnableQueryRewrite("rewrite".equals(normalizedStrategy) || "full".equals(normalizedStrategy));
    experiment.setEnableReranker("full".equals(normalizedStrategy));
    experiment.setStatus("running");
    experiment.setCreatedAt(LocalDateTime.now());
    evalExperimentMapper.insert(experiment);

    int total = questions.size();
    int top1HitCount = 0;
    int top3HitCount = 0;
    long latencySum = 0;
    double mrrSum = 0.0;
    List<EvalResult> evalResults = new ArrayList<>(total);

    try {
      long searchStart = System.currentTimeMillis();
      for (EvalQuestion question : questions) {
        List<Long> expectedChunkIds = parseLongList(question.getExpectedDocIds());
        long start = System.currentTimeMillis();
        List<SearchResult> results =
            search(question.getQuestion(), dataset.getKbId(), normalizedStrategy, runTopK, runVectorWeight);
        int latencyMs = (int) (System.currentTimeMillis() - start);

        EvalResult evalResult = buildEvalResult(experiment.getId(), question.getId(), expectedChunkIds, results, runTopK);
        evalResult.setLatencyMs(latencyMs);
        evalResults.add(evalResult);

        if (evalResult.getHitAt() != null && evalResult.getHitAt() == 1) {
          top1HitCount++;
        }
        if (Boolean.TRUE.equals(evalResult.getHit())) {
          top3HitCount++;
        }
        latencySum += latencyMs;
        mrrSum += mrrFromHitAt(evalResult.getHitAt());
      }
      long searchElapsed = System.currentTimeMillis() - searchStart;
      long insertStart = System.currentTimeMillis();
      insertEvalResultsBatch(evalResults);
      log.info(
          "Eval experiment questions completed: experimentId={} questions={} searchElapsedMs={} batchInsertElapsedMs={}",
          experiment.getId(),
          total,
          searchElapsed,
          System.currentTimeMillis() - insertStart);
    } catch (Exception e) {
      log.error("Experiment run failed: {}", e.getMessage(), e);
      self.markAsFailed(experiment);
      throw new BizException(500, "实验运行失败: " + e.getMessage());
    }

    experiment.setTotalQuestions(total);
    experiment.setTop1HitCount(top1HitCount);
    experiment.setTop3HitCount(top3HitCount);
    experiment.setTop1HitRate(rate(top1HitCount, total));
    experiment.setTop3HitRate(rate(top3HitCount, total));
    experiment.setMrr(BigDecimal.valueOf(mrrSum / total).setScale(4, RoundingMode.HALF_UP));
    experiment.setAvgLatencyMs((int) Math.round((double) latencySum / total));
    experiment.setStatus("completed");
    evalExperimentMapper.updateById(experiment);

    return getDetail(experiment.getId());
  }

  @Override
  public List<EvalExperimentVO> listRecent() {
    List<EvalExperiment> experiments =
        evalExperimentMapper.selectList(
            new LambdaQueryWrapper<EvalExperiment>()
                .orderByDesc(EvalExperiment::getCreatedAt)
                .last("LIMIT 20"));
    if (experiments.isEmpty()) {
      return Collections.emptyList();
    }

    Map<Long, String> datasetNames = datasetNames(experiments.stream().map(EvalExperiment::getDatasetId).toList());
    return experiments.stream().map(exp -> toSummaryVO(exp, datasetNames.get(exp.getDatasetId()))).toList();
  }

  @Override
  public EvalExperimentVO getDetail(Long id) {
    EvalExperiment experiment = evalExperimentMapper.selectById(id);
    if (experiment == null) {
      throw new BizException(404, "评测实验不存在");
    }
    EvalDataset dataset = requireDataset(experiment.getDatasetId());

    List<EvalResult> evalResults =
        evalResultMapper.selectList(
            new LambdaQueryWrapper<EvalResult>()
                .eq(EvalResult::getExperimentId, id)
                .orderByAsc(EvalResult::getQuestionId));
    List<Long> questionIds = evalResults.stream().map(EvalResult::getQuestionId).toList();
    Map<Long, EvalQuestion> questionMap =
        evalQuestionMapper
            .selectList(new LambdaQueryWrapper<EvalQuestion>().in(!questionIds.isEmpty(), EvalQuestion::getId, questionIds))
            .stream()
            .collect(LinkedHashMap::new, (m, q) -> m.put(q.getId(), q), LinkedHashMap::putAll);

    Set<Long> allChunkIds = new java.util.LinkedHashSet<>();
    for (EvalResult result : evalResults) {
      allChunkIds.addAll(parseLongList(result.getRecalledChunkIds()));
      EvalQuestion q = questionMap.get(result.getQuestionId());
      allChunkIds.addAll(parseLongList(q != null ? q.getExpectedDocIds() : null));
    }
    Map<Long, ChunkPreviewVO> chunkMap = loadChunkPreviews(allChunkIds);

    List<EvalResultDetailVO> details = new ArrayList<>();
    List<EvalFailureSampleVO> failureSamples = new ArrayList<>();
    for (EvalResult result : evalResults) {
      EvalQuestion q = questionMap.get(result.getQuestionId());
      EvalResultDetailVO detail = new EvalResultDetailVO();
      detail.setQuestionId(result.getQuestionId());
      detail.setQuestion(q != null ? q.getQuestion() : "");
      List<Long> expected = q != null ? parseLongList(q.getExpectedDocIds()) : List.of();
      List<Long> recalled = parseLongList(result.getRecalledChunkIds());
      List<ChunkPreviewVO> expectedChunks = buildChunkPreviews(expected, chunkMap);
      List<ChunkPreviewVO> recalledChunks = buildChunkPreviews(recalled, chunkMap);
      detail.setExpectedChunkIds(expected);
      detail.setRecalledChunkIds(recalled);
      detail.setExpectedChunks(expectedChunks);
      detail.setRecalledChunks(recalledChunks);
      detail.setHitAt(result.getHitAt());
      detail.setTop1Hit(result.getHitAt() != null && result.getHitAt() == 1);
      detail.setTop3Hit(Boolean.TRUE.equals(result.getHit()));
      detail.setMrr(mrrFromHitAt(result.getHitAt()));
      detail.setLatencyMs(result.getLatencyMs());
      detail.setFailureReason(result.getFailureReason());
      Integer expectedBestRank = bestRank(recalled, expected);
      detail.setExpectedBestRank(expectedBestRank);
      detail.setExpectedInTopK(expectedBestRank != null);
      detail.setExpectedInTop3(expectedBestRank != null && expectedBestRank <= 3);
      detail.setRecalledCount(recalled.size());
      detail.setSuggestion(
          buildFailureSuggestion(
              result.getFailureReason(), experiment.getStrategy(), expectedBestRank, recalled.size()));
      details.add(detail);

      if (!Boolean.TRUE.equals(result.getHit())) {
        EvalFailureSampleVO sample = new EvalFailureSampleVO();
        sample.setQuestionId(result.getQuestionId());
        sample.setQuestion(detail.getQuestion());
        sample.setFailureReason(result.getFailureReason());
        sample.setSuggestion(detail.getSuggestion());
        sample.setExpectedChunkIds(expected);
        sample.setRecalledChunkIds(recalled);
        sample.setExpectedChunks(expectedChunks);
        sample.setRecalledChunks(recalledChunks);
        sample.setExpectedBestRank(expectedBestRank);
        sample.setExpectedInTopK(expectedBestRank != null);
        sample.setExpectedInTop3(expectedBestRank != null && expectedBestRank <= 3);
        sample.setRecalledCount(recalled.size());
        failureSamples.add(sample);
      }
    }

    EvalExperimentVO vo = toSummaryVO(experiment, dataset.getName());
    vo.setResults(details);
    vo.setFailureSamples(failureSamples);
    return vo;
  }

  @Override
  @Transactional
  public void delete(Long id) {
    EvalExperiment experiment = evalExperimentMapper.selectById(id);
    if (experiment == null) {
      throw new BizException(404, "评测实验不存在");
    }
    evalResultMapper.delete(
        new LambdaQueryWrapper<EvalResult>().eq(EvalResult::getExperimentId, id));
    evalExperimentMapper.deleteById(id);
  }

  private EvalExperimentVO toSummaryVO(EvalExperiment entity, String datasetName) {
    EvalExperimentVO vo = new EvalExperimentVO();
    vo.setId(entity.getId());
    vo.setDatasetId(entity.getDatasetId());
    vo.setDatasetName(datasetName);
    vo.setStrategy(entity.getStrategy());
    vo.setTotalQuestions(entity.getTotalQuestions());
    vo.setTop1HitCount(entity.getTop1HitCount());
    vo.setTop3HitCount(entity.getTop3HitCount());
    vo.setTop1HitRate(entity.getTop1HitRate());
    vo.setTop3HitRate(entity.getTop3HitRate());
    vo.setMrr(entity.getMrr());
    vo.setAvgLatencyMs(entity.getAvgLatencyMs());
    vo.setStatus(entity.getStatus());
    vo.setCreatedAt(entity.getCreatedAt());
    return vo;
  }

  private EvalResult buildEvalResult(
      Long experimentId,
      Long questionId,
      List<Long> expectedChunkIds,
      List<SearchResult> results,
      int topK) {
    List<Long> recalledChunkIds =
        results.stream()
            .limit(topK)
            .map(SearchResult::getChunkId)
            .filter(id -> id != null && id > 0)
            .toList();

    Set<Long> expectedSet = toLongSet(expectedChunkIds);

    int hitAt = 0;
    for (int i = 0; i < Math.min(3, recalledChunkIds.size()); i++) {
      Long chunkId = recalledChunkIds.get(i);
      if (chunkId != null && expectedSet.contains(chunkId.longValue())) {
        hitAt = i + 1;
        break;
      }
    }
    log.info("buildEvalResult questionId={} expected={} recalled(top3)={} hitAt={} expectedClass={} recalledClass={}",
        questionId,
        expectedChunkIds.stream().map(n -> n.getClass().getSimpleName() + "(" + n + ")").toList(),
        recalledChunkIds.subList(0, Math.min(3, recalledChunkIds.size())).stream().map(n -> n.getClass().getSimpleName() + "(" + n + ")").toList(),
        hitAt,
        expectedChunkIds.isEmpty() ? "empty" : expectedChunkIds.get(0).getClass().getSimpleName(),
        recalledChunkIds.isEmpty() ? "empty" : recalledChunkIds.get(0).getClass().getSimpleName());
    boolean top3Hit = hitAt > 0;

    EvalResult row = new EvalResult();
    row.setExperimentId(experimentId);
    row.setQuestionId(questionId);
    row.setHit(top3Hit);
    row.setHitAt(hitAt == 0 ? null : hitAt);
    row.setRecalledChunkIds(writeJson(recalledChunkIds));
    row.setScore(results.isEmpty() ? BigDecimal.ZERO : BigDecimal.valueOf(primaryScore(results.get(0))));
    row.setFailureReason(
        top3Hit ? null : classifyFailure(expectedChunkIds, recalledChunkIds, bestRank(recalledChunkIds, expectedSet)));
    return row;
  }

  private List<SearchResult> search(String query, Long kbId, String strategy, int topK, double vectorWeight) {
    List<Long> kbIds = kbId == null ? null : List.of(kbId);
    return retrievalService
        .retrieve(query, kbIds, null, strategy, vectorWeight, topK, topK)
        .getResults();
  }

  private void insertEvalResultsBatch(List<EvalResult> evalResults) {
    if (evalResults == null || evalResults.isEmpty()) {
      return;
    }
    String sql =
        """
        INSERT INTO eval_results
          (experiment_id, question_id, hit, hit_at, recalled_chunk_ids, score, latency_ms, failure_reason)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
    jdbcTemplate.batchUpdate(
        sql,
        evalResults,
        100,
        (ps, item) -> {
          ps.setLong(1, item.getExperimentId());
          ps.setLong(2, item.getQuestionId());
          ps.setBoolean(3, Boolean.TRUE.equals(item.getHit()));
          if (item.getHitAt() == null) {
            ps.setObject(4, null);
          } else {
            ps.setInt(4, item.getHitAt());
          }
          ps.setString(5, item.getRecalledChunkIds());
          ps.setBigDecimal(6, item.getScore());
          if (item.getLatencyMs() == null) {
            ps.setObject(7, null);
          } else {
            ps.setInt(7, item.getLatencyMs());
          }
          ps.setString(8, item.getFailureReason());
        });
  }

  private String classifyFailure(
      List<Long> expectedChunkIds, List<Long> recalledChunkIds, Integer expectedBestRank) {
    if (expectedChunkIds == null || expectedChunkIds.isEmpty()) {
      return "标注缺失";
    }
    if (recalledChunkIds == null || recalledChunkIds.isEmpty()) {
      return "无召回结果";
    }
    if (expectedBestRank == null) {
      return "召回不足";
    }
    if (expectedBestRank > 3) {
      return "排序不足";
    }
    return "未知失败";
  }

  private static Integer bestRank(List<Long> recalledChunkIds, List<Long> expectedChunkIds) {
    return bestRank(recalledChunkIds, toLongSet(expectedChunkIds));
  }

  private static Integer bestRank(List<Long> recalledChunkIds, Set<Long> expectedSet) {
    if (recalledChunkIds == null || recalledChunkIds.isEmpty() || expectedSet == null || expectedSet.isEmpty()) {
      return null;
    }
    for (int i = 0; i < recalledChunkIds.size(); i++) {
      Long chunkId = recalledChunkIds.get(i);
      if (chunkId != null && expectedSet.contains(chunkId.longValue())) {
        return i + 1;
      }
    }
    return null;
  }

  private static Set<Long> toLongSet(List<Long> values) {
    if (values == null || values.isEmpty()) {
      return Collections.emptySet();
    }
    Set<Long> result = new HashSet<>();
    for (Long value : values) {
      if (value != null) {
        result.add(value.longValue());
      }
    }
    return result;
  }

  private static String buildFailureSuggestion(
      String reason, String strategy, Integer expectedBestRank, int recalledCount) {
    if ("标注缺失".equals(reason)) {
      return "该题没有标准 Chunk，实验无法判断命中。请人工选择期望 Chunk；快速体验自动生成的数据集也需要复核标注。";
    }
    if ("无召回结果".equals(reason)) {
      return "当前策略没有返回任何 Chunk。先确认文档已处理完成且知识库范围正确，再尝试提高 TopK 或切换到 hybrid/full。";
    }
    if ("召回不足".equals(reason)) {
      if ("keyword".equals(strategy)) {
        return "期望 Chunk 未进入 TopK。BM25 可能缺少同义词或关键词，建议补充问题关键词、改用 hybrid，或提高 TopK 后复测。";
      }
      if ("vector".equals(strategy)) {
        return "期望 Chunk 未进入 TopK。向量召回语义不够接近，建议检查分块内容、提高 TopK，或改用 hybrid 结合关键词召回。";
      }
      if ("rewrite".equals(strategy)) {
        return "期望 Chunk 未进入 TopK。Query 改写可能偏离原意，建议查看改写结果，减少过度改写或改用 hybrid/full。";
      }
      return "期望 Chunk 未进入 TopK。建议检查标注是否正确、提高 TopK、优化分块，或补充文档中的关键词表达。";
    }
    if ("排序不足".equals(reason)) {
      String rankText = expectedBestRank == null ? "TopK 内较靠后位置" : "第 " + expectedBestRank + " 位";
      if ("full".equals(strategy)) {
        return "期望 Chunk 已进入 TopK 但位于" + rankText + "，未进 Top3。full 已启用 Rerank，建议检查标注 Chunk 内容是否足够完整，或扩大 Rerank TopN。";
      }
      if ("hybrid".equals(strategy)) {
        return "期望 Chunk 已进入 TopK 但位于" + rankText + "，未进 Top3。建议微调向量/BM25 权重，或切换 full 引入 Rerank。";
      }
      return "期望 Chunk 已进入 TopK 但位于" + rankText + "，未进 Top3。建议使用 hybrid/full 改善排序，或提高 TopK 观察命中位置。";
    }
    if ("排序错误".equals(reason)) {
      return "旧实验使用了粗粒度失败分类。建议重新运行实验，使用新版原因分析。";
    }
    return "建议检查标准 Chunk 标注、实际召回列表和文档分块内容后重新运行实验。当前召回数：" + recalledCount + "。";
  }

  private static String normalizeStrategy(String strategy) {
    if ("keyword".equalsIgnoreCase(strategy)) return "keyword";
    if ("hybrid".equalsIgnoreCase(strategy)) return "hybrid";
    if ("full".equalsIgnoreCase(strategy)) return "full";
    if ("rewrite".equalsIgnoreCase(strategy)) return "rewrite";
    return "vector";
  }

  private EvalDataset requireDataset(Long datasetId) {
    EvalDataset dataset = evalDatasetMapper.selectById(datasetId);
    if (dataset == null) {
      throw new BizException(404, "评测数据集不存在");
    }
    return dataset;
  }

  private Map<Long, String> datasetNames(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return Collections.emptyMap();
    }
    return evalDatasetMapper
        .selectList(new LambdaQueryWrapper<EvalDataset>().in(EvalDataset::getId, ids))
        .stream()
        .collect(HashMap::new, (m, item) -> m.put(item.getId(), item.getName()), HashMap::putAll);
  }

  private List<Long> parseLongList(String json) {
    if (!StringUtils.hasText(json)) {
      return Collections.emptyList();
    }
    try {
      return objectMapper.readValue(json, LONG_LIST_TYPE);
    } catch (JsonProcessingException e) {
      return Collections.emptyList();
    }
  }

  private String writeJson(List<Long> list) {
    try {
      return objectMapper.writeValueAsString(list);
    } catch (JsonProcessingException e) {
      return "[]";
    }
  }

  private Map<Long, ChunkPreviewVO> loadChunkPreviews(Set<Long> chunkIds) {
    if (chunkIds == null || chunkIds.isEmpty()) {
      return Collections.emptyMap();
    }
    return documentChunkMapper.selectBatchIds(chunkIds).stream()
        .collect(
            LinkedHashMap::new,
            (m, chunk) -> m.put(chunk.getId(), toChunkPreview(chunk)),
            LinkedHashMap::putAll);
  }

  private List<ChunkPreviewVO> buildChunkPreviews(List<Long> chunkIds, Map<Long, ChunkPreviewVO> chunkMap) {
    if (chunkIds == null || chunkIds.isEmpty() || chunkMap == null || chunkMap.isEmpty()) {
      return Collections.emptyList();
    }
    List<ChunkPreviewVO> result = new ArrayList<>();
    for (Long chunkId : chunkIds) {
      if (chunkId == null) {
        continue;
      }
      ChunkPreviewVO preview = chunkMap.get(chunkId);
      if (preview != null) {
        result.add(preview);
      }
    }
    return result;
  }

  private static ChunkPreviewVO toChunkPreview(DocumentChunk chunk) {
    ChunkPreviewVO vo = new ChunkPreviewVO();
    vo.setChunkId(chunk.getId());
    vo.setChunkIndex(chunk.getChunkIndex());
    vo.setContent(chunk.getContent());
    vo.setTokenCount(chunk.getTokenCount());
    return vo;
  }

  private static BigDecimal rate(int hit, int total) {
    if (total <= 0) return BigDecimal.ZERO;
    return BigDecimal.valueOf((double) hit / total).setScale(4, RoundingMode.HALF_UP);
  }

  private static double mrrFromHitAt(Integer hitAt) {
    if (hitAt == null || hitAt <= 0) return 0.0;
    return 1.0 / hitAt;
  }

  private static double primaryScore(SearchResult r) {
    double raw;
    if (r.getFinalScore() != 0) raw = r.getFinalScore();
    else if (r.getBm25Score() != 0) raw = r.getBm25Score();
    else raw = r.getVectorScore();
    // BM25 分数可能 > 10，DECIMAL(5,4) 只能存 0~9.9999，做归一化
    if (raw > 1) raw = raw / (1 + raw);
    return raw;
  }

  /** 在新事务中标记实验失败，避免被已中止的事务影响 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markAsFailed(EvalExperiment experiment) {
    experiment.setStatus("failed");
    evalExperimentMapper.updateById(experiment);
  }
}
