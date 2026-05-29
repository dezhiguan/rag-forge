package com.ragforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.mapper.EvalDatasetMapper;
import com.ragforge.mapper.EvalExperimentMapper;
import com.ragforge.mapper.EvalQuestionMapper;
import com.ragforge.mapper.EvalResultMapper;
import com.ragforge.model.entity.EvalDataset;
import com.ragforge.model.entity.EvalExperiment;
import com.ragforge.model.entity.EvalQuestion;
import com.ragforge.model.entity.EvalResult;
import com.ragforge.model.vo.EvalExperimentVO;
import com.ragforge.model.vo.EvalFailureSampleVO;
import com.ragforge.model.vo.EvalResultDetailVO;
import com.ragforge.search.EsSearchService;
import com.ragforge.search.HybridSearchService;
import com.ragforge.search.RerankerClient;
import com.ragforge.search.RerankerClient.RerankResult;
import com.ragforge.search.SearchResult;
import com.ragforge.search.VectorSearchService;
import com.ragforge.service.EvalExperimentService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
  private final VectorSearchService vectorSearchService;
  private final EsSearchService esSearchService;
  private final HybridSearchService hybridSearchService;
  private final RerankerClient rerankerClient;
  private final ObjectMapper objectMapper;

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
    experiment.setEnableQueryRewrite(false);
    experiment.setEnableReranker("full".equals(normalizedStrategy));
    experiment.setStatus("running");
    experiment.setCreatedAt(LocalDateTime.now());
    evalExperimentMapper.insert(experiment);

    int total = questions.size();
    int top1HitCount = 0;
    int top3HitCount = 0;
    long latencySum = 0;
    double mrrSum = 0.0;

    try {
      for (EvalQuestion question : questions) {
        List<Long> expectedChunkIds = parseLongList(question.getExpectedDocIds());
        long start = System.currentTimeMillis();
        List<SearchResult> results =
            search(question.getQuestion(), dataset.getKbId(), normalizedStrategy, runTopK, runVectorWeight);
        int latencyMs = (int) (System.currentTimeMillis() - start);

        EvalResult evalResult = buildEvalResult(experiment.getId(), question.getId(), expectedChunkIds, results, runTopK);
        evalResult.setLatencyMs(latencyMs);
        evalResultMapper.insert(evalResult);

        if (evalResult.getHitAt() != null && evalResult.getHitAt() == 1) {
          top1HitCount++;
        }
        if (Boolean.TRUE.equals(evalResult.getHit())) {
          top3HitCount++;
        }
        latencySum += latencyMs;
        mrrSum += mrrFromHitAt(evalResult.getHitAt());
      }
    } catch (Exception e) {
      experiment.setStatus("failed");
      evalExperimentMapper.updateById(experiment);
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

    List<EvalResultDetailVO> details = new ArrayList<>();
    List<EvalFailureSampleVO> failureSamples = new ArrayList<>();
    for (EvalResult result : evalResults) {
      EvalQuestion q = questionMap.get(result.getQuestionId());
      EvalResultDetailVO detail = new EvalResultDetailVO();
      detail.setQuestionId(result.getQuestionId());
      detail.setQuestion(q != null ? q.getQuestion() : "");
      List<Long> expected = q != null ? parseLongList(q.getExpectedDocIds()) : List.of();
      List<Long> recalled = parseLongList(result.getRecalledChunkIds());
      detail.setExpectedChunkIds(expected);
      detail.setRecalledChunkIds(recalled);
      detail.setHitAt(result.getHitAt());
      detail.setTop1Hit(result.getHitAt() != null && result.getHitAt() == 1);
      detail.setTop3Hit(Boolean.TRUE.equals(result.getHit()));
      detail.setMrr(mrrFromHitAt(result.getHitAt()));
      detail.setLatencyMs(result.getLatencyMs());
      detail.setFailureReason(result.getFailureReason());
      details.add(detail);

      if (!Boolean.TRUE.equals(result.getHit())) {
        EvalFailureSampleVO sample = new EvalFailureSampleVO();
        sample.setQuestionId(result.getQuestionId());
        sample.setQuestion(detail.getQuestion());
        sample.setFailureReason(result.getFailureReason());
        sample.setExpectedChunkIds(expected);
        sample.setRecalledChunkIds(recalled);
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

    // 转为 Set<Long> 避免 Jackson 反序列化可能产生的 Integer/Long 类型不匹配
    java.util.Set<Long> expectedSet = expectedChunkIds.stream()
        .map(Number::longValue)
        .collect(java.util.stream.Collectors.toSet());

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
    row.setFailureReason(top3Hit ? null : classifyFailure(results, topK));
    return row;
  }

  private List<SearchResult> search(
      String query, Long kbId, String strategy, int topK, double vectorWeight) {
    List<Long> kbIds = kbId == null ? null : List.of(kbId);
    return switch (strategy) {
      case "keyword" -> esSearchService.search(query, kbIds, null, topK);
      case "hybrid" -> hybridSearchService.search(query, kbIds, null, topK, vectorWeight);
      case "full" -> rerank(
          hybridSearchService.search(query, kbIds, null, topK, vectorWeight), query, topK);
      default -> vectorSearchService.search(query, kbIds, null, topK);
    };
  }

  private List<SearchResult> rerank(List<SearchResult> source, String query, int topK) {
    if (source.isEmpty()) {
      return source;
    }
    List<RerankResult> reranked =
        rerankerClient.rerank(query, source.stream().map(SearchResult::getContent).toList(), Math.min(topK, source.size()))
            .getResults();
    if (reranked == null || reranked.isEmpty()) {
      return source;
    }
    List<SearchResult> reordered = new ArrayList<>();
    Set<Integer> used = new HashSet<>();
    for (RerankResult rank : reranked) {
      int idx = rank.getIndex();
      if (idx < 0 || idx >= source.size()) {
        continue;
      }
      SearchResult item = source.get(idx);
      item.setFinalScore(rank.getScore());
      reordered.add(item);
      used.add(idx);
    }
    for (int i = 0; i < source.size(); i++) {
      if (!used.contains(i)) {
        reordered.add(source.get(i));
      }
    }
    reordered.sort(Comparator.comparingDouble(SearchResult::getFinalScore).reversed());
    return reordered.size() > topK ? reordered.subList(0, topK) : reordered;
  }

  private String classifyFailure(List<SearchResult> results, int topK) {
    int actual = Math.min(3, results.size());
    if (results.size() < topK) {
      return "标注缺失";
    }
    if (actual == 0) {
      return "召回不足";
    }
    boolean allLow = true;
    for (int i = 0; i < actual; i++) {
      if (primaryScore(results.get(i)) >= 0.1) {
        allLow = false;
        break;
      }
    }
    return allLow ? "召回不足" : "排序错误";
  }

  private static String normalizeStrategy(String strategy) {
    if ("keyword".equalsIgnoreCase(strategy)) return "keyword";
    if ("hybrid".equalsIgnoreCase(strategy)) return "hybrid";
    if ("full".equalsIgnoreCase(strategy)) return "full";
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

  private static BigDecimal rate(int hit, int total) {
    if (total <= 0) return BigDecimal.ZERO;
    return BigDecimal.valueOf((double) hit / total).setScale(4, RoundingMode.HALF_UP);
  }

  private static double mrrFromHitAt(Integer hitAt) {
    if (hitAt == null || hitAt <= 0) return 0.0;
    return 1.0 / hitAt;
  }

  private static double primaryScore(SearchResult r) {
    if (r.getFinalScore() != 0) return r.getFinalScore();
    if (r.getBm25Score() != 0) return r.getBm25Score();
    return r.getVectorScore();
  }
}

