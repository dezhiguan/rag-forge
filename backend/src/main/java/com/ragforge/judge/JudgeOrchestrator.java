package com.ragforge.judge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.answer.AnswerModels.Citation;
import com.ragforge.judge.sampler.AnswerJudgeMessage;
import com.ragforge.mapper.AnswerLogMapper;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.JudgeResultMapper;
import com.ragforge.metrics.RagforgeMetrics;
import com.ragforge.model.entity.AnswerLog;
import com.ragforge.model.entity.DocumentChunk;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ragforge.model.entity.JudgeResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class JudgeOrchestrator {

  private final AnswerLogMapper answerLogMapper;
  private final DocumentChunkMapper documentChunkMapper;
  private final JudgeResultMapper judgeResultMapper;
  private final JudgeScorer scorer;
  private final RagforgeMetrics metrics;
  private final ObjectMapper objectMapper;
  private final com.ragforge.modelcenter.ModelUsageRecorder modelUsageRecorder;

  @Value("${app.deepseek.model:deepseek-v4-flash}")
  private String judgeModel;

  @Value("${ragforge.judge.stability-check.enabled:false}")
  private boolean stabilityCheckEnabled;

  public void judge(AnswerJudgeMessage msg) {
    if (msg == null) {
      return;
    }

    AnswerLog answerLog = answerLogMapper.selectByIdWithKbIdsCsv(msg.getAnswerLogId());
    if (answerLog == null) {
      log.warn("AnswerLog not found: {}", msg.getAnswerLogId());
      return;
    }

    Long[] kbIds = parseKbIds(answerLog);

    JudgeContext ctx = buildContext(answerLog);
    JudgeResult result = new JudgeResult();
    result.setAnswerLogId(answerLog.getId());
    result.setKbIds(kbIds);
    result.setQuery(answerLog.getQuery());
    result.setSource(msg.getSource() == null ? "PRODUCTION" : msg.getSource());
    result.setGoldenQuestionId(msg.getGoldenQuestionId());
    result.setStatus("RUNNING");
    result.setTenantId(answerLog.getTenantId());
    result.setJudgeModel(judgeModel);
    result.setJudgePromptVersion("v1");

    BigDecimal cost = BigDecimal.ZERO;
    long latency = 0L;
    long judgePromptTokens = 0L;
    long judgeCompletionTokens = 0L;

    judgeResultMapper.insert(result);

    try {
      JudgeScore faithfulness = scoreDimension(ctx, ScoreDimension.FAITHFULNESS);
      JudgeScore contextPrecision = scoreDimension(ctx, ScoreDimension.CONTEXT_PRECISION);
      JudgeScore answerRelevance = scoreDimension(ctx, ScoreDimension.ANSWER_RELEVANCE);
      JudgeContext compositeCtx =
          ctx.withPriorScores(
              faithfulness.getScore(), contextPrecision.getScore(), answerRelevance.getScore());
      JudgeScore overall = scoreDimension(compositeCtx, ScoreDimension.COMPOSITE);

      List<JudgeScore> scores = List.of(faithfulness, contextPrecision, answerRelevance, overall);
      applyDimensionResult(result, faithfulness);
      applyDimensionResult(result, contextPrecision);
      applyDimensionResult(result, answerRelevance);
      applyDimensionResult(result, overall);

      for (JudgeScore score : scores) {
        if (score != null && score.isSuccess()) {
          metrics.recordDeepSeekTokens(
              score.getPromptTokens() == null ? 0 : score.getPromptTokens(),
              score.getCompletionTokens() == null ? 0 : score.getCompletionTokens());
          judgePromptTokens += score.getPromptTokens() == null ? 0 : score.getPromptTokens();
          judgeCompletionTokens += score.getCompletionTokens() == null ? 0 : score.getCompletionTokens();
          recordScore(result.getSource(), score, kbIds);
          cost = cost.add(safeCost(score));
          latency += safeLatency(score);
        }
      }

      result.setJudgeRawResponse(buildRawResponse(scores));

      if (scores.stream().allMatch(score -> score != null && score.isSuccess())) {
        result.setStatus("COMPLETED");
      } else {
        result.setStatus("FAILED");
        result.setFailureReason(firstFailure(faithfulness, contextPrecision, answerRelevance, overall));
      }
    } catch (Exception e) {
      log.error("Judge orchestration failed: answerLogId={}", answerLog.getId(), e);
      result.setStatus("FAILED");
      result.setFailureReason(e.getMessage() == null ? "judge failed" : e.getMessage());
    } finally {
      result.setJudgeCostCny(cost);
      result.setJudgeLatencyMs(Math.toIntExact(Math.max(0L, latency)));
      // truncate failure_reason 防止超过 VARCHAR(256)（V32 迁移前兜底）
      String reason = result.getFailureReason();
      if (reason != null && reason.length() > 240) {
        result.setFailureReason(reason.substring(0, 237) + "...");
      }
      try {
        judgeResultMapper.updateById(result);
      } catch (Exception updateEx) {
        log.error(
            "Judge updateById failed; trying status-only fallback. answerLogId={}",
            answerLog.getId(),
            updateEx);
        try {
          judgeResultMapper.update(
              null,
              new LambdaUpdateWrapper<JudgeResult>()
                  .eq(JudgeResult::getId, result.getId())
                  .set(JudgeResult::getStatus, "FAILED")
                  .set(JudgeResult::getFailureReason, "PERSISTENCE_FAILED"));
        } catch (Exception fallbackEx) {
          log.error("Judge status-only fallback also failed. id={}", result.getId(), fallbackEx);
        }
      }
    }

    metrics.recordJudgeCost(result.getSource(), cost);
    modelUsageRecorder.record(
        new com.ragforge.modelcenter.ModelUsageEvent(
            judgeModel,
            com.ragforge.modelcenter.Purpose.JUDGE,
            judgePromptTokens,
            judgeCompletionTokens,
            latency,
            "COMPLETED".equals(result.getStatus())));
  }

  private JudgeScore scoreDimension(JudgeContext ctx, ScoreDimension dimension) {
    return stabilityCheckEnabled ? scorer.scoreWithRetry(ctx, dimension, 2) : scorer.score(ctx, dimension);
  }

  private void applyDimensionResult(JudgeResult result, JudgeScore score) {
    if (score == null) {
      return;
    }
    if (score.getDimension() == ScoreDimension.FAITHFULNESS) {
      result.setFaithfulness(score.getScore());
    } else if (score.getDimension() == ScoreDimension.CONTEXT_PRECISION) {
      result.setContextPrecision(score.getScore());
    } else if (score.getDimension() == ScoreDimension.ANSWER_RELEVANCE) {
      result.setAnswerRelevance(score.getScore());
    } else if (score.getDimension() == ScoreDimension.COMPOSITE) {
      result.setOverallScore(score.getScore());
      result.setJudgeReasoning(score.getReasoning());
    }

    if (!score.isSuccess() && result.getFailureReason() == null) {
      result.setFailureReason(score.getFailureReason());
    }
  }

  private void recordScore(String source, JudgeScore score, Long[] kbIds) {
    if (!score.isSuccess() || score.getScore() == null) {
      return;
    }
    String dimension = score.getDimension().name();
    if (kbIds == null || kbIds.length == 0) {
      metrics.recordJudgeScore(dimension, null, score.getScore());
      return;
    }
    for (Long kbId : kbIds) {
      metrics.recordJudgeScore(dimension, kbId, score.getScore());
    }
  }

  private long safeLatency(JudgeScore score) {
    return score == null || score.getLatencyMs() == null ? 0L : score.getLatencyMs();
  }

  private BigDecimal safeCost(JudgeScore score) {
    return score == null || score.getCostCny() == null ? BigDecimal.ZERO : score.getCostCny();
  }

  private String firstFailure(JudgeScore... scores) {
    for (JudgeScore score : scores) {
      if (score != null && !score.isSuccess()) {
        return score.getFailureReason() == null || score.getFailureReason().isBlank()
            ? "judge failed"
            : score.getFailureReason();
      }
    }
    return null;
  }

  private JudgeContext buildContext(AnswerLog log) {
    JudgeContext context = new JudgeContext();
    context.setQuery(log.getQuery());
    context.setAnswer(log.getAnswer());
    context.setCitations(parseCitations(log.getCitationsSnapshot()));
    context.setChunks(resolveChunks(context.getCitations()));
    return context;
  }

  private List<Citation> parseCitations(String snapshot) {
    if (snapshot == null || snapshot.isBlank()) {
      return List.of();
    }
    try {
      List<Citation> citations =
          objectMapper.readValue(snapshot, new TypeReference<List<Citation>>() {});
      return citations == null ? List.of() : citations;
    } catch (Exception e) {
      log.warn("Parse citations_snapshot failed: {}", e.getMessage());
      return List.of();
    }
  }

  private List<JudgeContext.RetrievedChunk> resolveChunks(List<Citation> citations) {
    if (citations == null || citations.isEmpty()) {
      return List.of();
    }

    List<Long> chunkIds =
        citations.stream()
            .map(Citation::getChunkId)
            .filter(chunkId -> chunkId != null)
            .distinct()
            .toList();
    if (chunkIds.isEmpty()) {
      return List.of();
    }

    List<DocumentChunk> chunks = documentChunkMapper.selectBatchIds(chunkIds);
    Map<Long, String> contentById =
        chunks.stream().collect(Collectors.toMap(DocumentChunk::getId, DocumentChunk::getContent, (a, b) -> a));

    List<JudgeContext.RetrievedChunk> result = new ArrayList<>();
    for (Citation citation : citations) {
      Long chunkId = citation == null ? null : citation.getChunkId();
      if (chunkId == null) {
        continue;
      }
      String content = contentById.getOrDefault(chunkId, "");
      result.add(new JudgeContext.RetrievedChunk(chunkId, content, null));
    }
    return result;
  }

  private Long[] parseKbIds(AnswerLog log) {
    String csv = log == null ? null : log.getKbIdsCsv();
    if (csv == null || csv.isBlank()) {
      return new Long[0];
    }
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .map(this::parseLong)
        .filter(id -> id != null)
        .toArray(Long[]::new);
  }

  private Long parseLong(String raw) {
    try {
      return Long.valueOf(raw);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private String buildRawResponse(List<JudgeScore> scores) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (JudgeScore score : scores) {
      if (score == null) {
        continue;
      }
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("score", score.getScore());
      entry.put("reasoning", score.getReasoning());
      entry.put("issues", score.getIssues());
      entry.put("stable", score.isStable());
      entry.put("prompt_tokens", score.getPromptTokens());
      entry.put("completion_tokens", score.getCompletionTokens());
      if (score.getRawResponse() != null) {
        entry.put("raw", score.getRawResponse());
      }
      map.put(score.getDimension().name().toLowerCase(Locale.ROOT), entry);
    }
    try {
      return objectMapper.writeValueAsString(map);
    } catch (JsonProcessingException e) {
      return "{}";
    }
  }
}
