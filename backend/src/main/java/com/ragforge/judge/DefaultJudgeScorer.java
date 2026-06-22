package com.ragforge.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultJudgeScorer implements JudgeScorer {

  private final DeepSeekClient client;
  private final JudgePromptLibrary prompts;
  private final ObjectMapper objectMapper;

  @Override
  public JudgeScore score(JudgeContext context, ScoreDimension dim) {
    try {
      if (context == null) {
        return JudgeScore.failed(dim, "context 不能为空");
      }

      if (dim == ScoreDimension.COMPOSITE) {
        return scoreComposite(context);
      }

      String systemPrompt = prompts.systemFor(dim);
      String userPrompt = prompts.userFor(dim, context);
      DeepSeekClient.ChatResult chat = client.chat(systemPrompt, userPrompt);
      JsonNode json = objectMapper.readTree(chat.content());
      return parseScore(dim, json, chat);
    } catch (Exception e) {
      return JudgeScore.failed(dim, e.getMessage());
    }
  }

  private JudgeScore scoreComposite(JudgeContext context) {
    JudgeScore faithfulness = score(context, ScoreDimension.FAITHFULNESS);
    JudgeScore contextPrecision = score(context, ScoreDimension.CONTEXT_PRECISION);
    JudgeScore answerRelevance = score(context, ScoreDimension.ANSWER_RELEVANCE);

    if (!faithfulness.isSuccess() || !contextPrecision.isSuccess() || !answerRelevance.isSuccess()) {
      return JudgeScore.failed(ScoreDimension.COMPOSITE, "composite 维度依赖子维度失败");
    }

    String systemPrompt = prompts.systemFor(ScoreDimension.COMPOSITE);
    String userPrompt =
        prompts.userForComposite(
            context,
            faithfulness.getScore(),
            contextPrecision.getScore(),
            answerRelevance.getScore());

    try {
      DeepSeekClient.ChatResult chat = client.chat(systemPrompt, userPrompt);
      JsonNode json = objectMapper.readTree(chat.content());
      return parseComposite(json, chat);
    } catch (Exception e) {
      return JudgeScore.failed(ScoreDimension.COMPOSITE, e.getMessage());
    }
  }

  private JudgeScore parseScore(ScoreDimension dim, JsonNode json, DeepSeekClient.ChatResult chat)
      throws Exception {
    BigDecimal score = readNumeric(json, "score");
    String reasoning = text(json.path("reasoning"));
    List<String> issues = parseIssues(dim, json);
    return JudgeScore.success(
        dim,
        score,
        reasoning,
        issues,
        json.toString(),
        chat.latencyMs(),
        chat.estimateCostCny(),
        true);
  }

  private JudgeScore parseComposite(JsonNode json, DeepSeekClient.ChatResult chat) {
    BigDecimal score = readNumeric(json, "overall_score");
    String reasoning = text(json.path("reasoning"));
    String bottleneck = text(json.path("bottleneck"));
    List<String> issues = parseArrayTexts(json.path("improvements"));
    if (!bottleneck.isBlank()) {
      issues.add("bottleneck=" + bottleneck);
    }
    return JudgeScore.success(
        ScoreDimension.COMPOSITE,
        score,
        reasoning,
        issues,
        json.toString(),
        chat.latencyMs(),
        chat.estimateCostCny(),
        true);
  }

  private BigDecimal readNumeric(JsonNode json, String key) {
    JsonNode node = json.path(key);
    if (!node.isNumber()) {
      throw new IllegalArgumentException("缺失字段或字段类型非法: " + key);
    }
    return node.decimalValue().setScale(4, RoundingMode.HALF_UP);
  }

  private List<String> parseIssues(ScoreDimension dim, JsonNode json) {
    return switch (dim) {
      case FAITHFULNESS -> parseArrayTexts(json.path("hallucinated_claims"));
      case CONTEXT_PRECISION -> parseChunkRelevance(json.path("chunk_relevance"));
      case ANSWER_RELEVANCE -> parseArrayTexts(json.path("off_topic_parts"));
      case COMPOSITE -> parseArrayTexts(json.path("improvements"));
    };
  }

  private List<String> parseChunkRelevance(JsonNode chunkRelevance) {
    if (!chunkRelevance.isArray()) {
      return List.of();
    }
    List<String> issues = new ArrayList<>();
    for (JsonNode item : chunkRelevance) {
      if (!item.isObject()) {
        continue;
      }
      long chunkId = item.path("chunk_id").asLong(-1);
      boolean relevant = item.path("relevant").asBoolean(false);
      if (!relevant) {
        issues.add("chunk_id=" + chunkId + " relevant=false");
      }
    }
    return issues;
  }

  private List<String> parseArrayTexts(JsonNode arrayNode) {
    if (!arrayNode.isArray()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (JsonNode node : arrayNode) {
      String value = text(node);
      if (!value.isBlank()) {
        values.add(value);
      }
    }
    return values;
  }

  private String text(JsonNode node) {
    return node == null || node.isNull() ? "" : node.asText("").trim();
  }
}
