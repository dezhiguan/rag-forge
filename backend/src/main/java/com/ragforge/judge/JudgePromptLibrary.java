package com.ragforge.judge;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * LLM-as-Judge 评测提示词库。
 *
 * <p>每个维度遵循统一结构:身份 → 任务 → 输入 → 评分锚点 → 偏好防护 → 输出 JSON 契约。
 * 输出 JSON 字段名与 {@code DefaultJudgeScorer} 解析逻辑一一绑定,不得擅自更名。
 */
@Component
public class JudgePromptLibrary {

  public String systemFor(ScoreDimension dimension) {
    return switch (dimension) {
      case FAITHFULNESS -> """
          你是 RAG 幻觉检测专家。\
          你的唯一职责是判断答案是否被检索内容支持,不评价答案本身好坏、风格或完整性。\
          所有判断必须可追溯到原始 chunk 文本。""";
      case CONTEXT_PRECISION -> """
          你是检索质量评判专家。\
          你的唯一职责是判断每个 chunk 是否对回答 query 有实际帮助,不评价答案。\
          相关性以语义贡献为准,不以词面重叠为准。""";
      case ANSWER_RELEVANCE -> """
          你是答案切题度评判专家。\
          你的唯一职责是判断答案是否在回应 query,不评价答案是否正确、是否忠于上下文。\
          合理的拒答属于切题。""";
      case COMPOSITE -> """
          你是 RAG 系统综合评测专家。\
          你已知三项子维度分数,你的职责是给出综合判断、定位瓶颈、提出可执行改进建议。\
          综合分必须与子维度一致,不得自相矛盾。""";
    };
  }

  public String userFor(ScoreDimension dimension, JudgeContext context) {
    return switch (dimension) {
      case FAITHFULNESS -> faithfulnessPrompt(context);
      case CONTEXT_PRECISION -> contextPrecisionPrompt(context);
      case ANSWER_RELEVANCE -> answerRelevancePrompt(context);
      case COMPOSITE ->
          compositePrompt(
              context,
              BigDecimal.valueOf(-1),
              BigDecimal.valueOf(-1),
              BigDecimal.valueOf(-1));
    };
  }

  public String userForComposite(
      JudgeContext context, BigDecimal faithfulness, BigDecimal contextPrecision, BigDecimal answerRelevance) {
    return compositePrompt(context, faithfulness, contextPrecision, answerRelevance);
  }

  private String faithfulnessPrompt(JudgeContext context) {
    return """
        【任务】
        判断答案中的每一项事实陈述是否能从检索内容中找到直接出处,识别幻觉。

        【输入】
        Query: %s
        Retrieved Context:
        %s
        Generated Answer: %s

        【评分锚点】
        1.0 - 答案中所有事实陈述均能在 chunk 中找到直接出处,无任何编造
        0.7 - 大部分有出处,仅 1 处合理推断或轻微改写,无事实错误
        0.4 - 部分事实有出处,部分为模型自行补充或推测
        0.0 - 答案与上下文无关,或包含与上下文矛盾的事实错误

        【偏好防护】
        - 不因答案啰嗦或信息量大给高分(length bias)
        - 不因措辞流畅或风格亲切给高分(style bias)
        - 不因答案简短就判定为不忠实(短答案可以完全忠实)
        - 合理的拒答("根据资料无法回答")若未编造,应给 1.0
        - 仅以"是否能从 chunk 找到出处"为判断依据

        【输出严格 JSON,不含其他文字】
        {"score": 0.85, "reasoning": "≤80 字说明判分依据", "hallucinated_claims": ["列出答案中无法在 chunk 中找到出处的具体陈述,若无则为空数组"]}"""
        .formatted(
            safe(context == null ? null : context.getQuery()),
            renderChunks(context == null ? null : context.getChunks()),
            safe(context == null ? null : context.getAnswer()));
  }

  private String contextPrecisionPrompt(JudgeContext context) {
    return """
        【任务】
        逐个判断检索 top-K chunks 是否对回答 query 有实际帮助,并给出整体精度分。

        【输入】
        Query: %s
        Retrieved Context:
        %s
        Generated Answer: %s

        【评分锚点】
        1.0 - 所有 chunk 都直接相关,无冗余,无干扰
        0.7 - ≥70%% 的 chunk 相关,少数偏弱但不影响回答
        0.4 - 约半数相关,半数无关或重复
        0.0 - 几乎全部无关,检索完全失败

        【偏好防护】
        - 相关性以"是否对回答 query 有语义贡献"为准,不以词面重叠为准
        - 不因 chunk 数量多就降低精度(quantity bias)
        - 不因 chunk 文本长就判定为更相关(length bias)
        - 不因 chunk 出现在 top1 就默认相关(positional bias)
        - 不参考 answer 的内容反推 chunk 是否相关,仅基于 query

        【输出严格 JSON,不含其他文字】
        {"score": 0.60, "reasoning": "≤80 字说明判分依据", "chunk_relevance": [{"chunk_id": 123, "relevant": true}, {"chunk_id": 456, "relevant": false}]}"""
        .formatted(
            safe(context == null ? null : context.getQuery()),
            renderChunks(context == null ? null : context.getChunks()),
            safe(context == null ? null : context.getAnswer()));
  }

  private String answerRelevancePrompt(JudgeContext context) {
    return """
        【任务】
        判断答案是否在回应 query 本身,识别偏题、答非所问、绕弯子。

        【输入】
        Query: %s
        Retrieved Context:
        %s
        Generated Answer: %s

        【评分锚点】
        1.0 - 答案完全围绕 query,直击要点,无偏题
        0.7 - 主要回应了 query,夹带少量(<20%%)无关内容或铺垫
        0.4 - 部分回应,但偏题或答非所问占比 ≥ 30%%
        0.0 - 完全跑题,或在回答另一个问题

        【偏好防护】
        - 不因答案信息量大就给高分(verbosity bias)
        - 不因措辞礼貌、有套话开场就给高分(politeness bias)
        - 不因答案准确(事实正确)就给高分 -- 准确不等于切题
        - 合理拒答("我无法回答 X,因为资料中没有相关信息")应视为切题,给 1.0
        - 此维度不评判忠实度,即使答案是编造的,只要它回应了 query,切题度仍可高

        【输出严格 JSON,不含其他文字】
        {"score": 0.75, "reasoning": "≤80 字说明判分依据", "off_topic_parts": ["列出答案中偏离 query 的段落,若无则为空数组"]}"""
        .formatted(
            safe(context == null ? null : context.getQuery()),
            renderChunks(context == null ? null : context.getChunks()),
            safe(context == null ? null : context.getAnswer()));
  }

  private String compositePrompt(
      JudgeContext context, BigDecimal faithfulness, BigDecimal contextPrecision, BigDecimal answerRelevance) {
    String faith = formatScoreOrNa(faithfulness);
    String precision = formatScoreOrNa(contextPrecision);
    String answer = formatScoreOrNa(answerRelevance);
    String mean = formatMeanOrNa(faithfulness, contextPrecision, answerRelevance);

    return """
        【已知子维度分数】
        - Faithfulness(忠实度): %s
        - ContextPrecision(上下文精度): %s
        - AnswerRelevance(切题度): %s
        - 子维度均值: %s

        【任务】
        基于子维度分数与原始 RAG 上下文,给出综合判断、瓶颈定位与可执行改进建议。

        【输入】
        Query: %s
        Retrieved Context:
        %s
        Generated Answer: %s

        【评分锚点】
        1.0 - 三项子维度均 ≥ 0.85,答案对用户具有真实价值
        0.7 - 三项子维度均 ≥ 0.6,有可定位的轻微短板
        0.4 - 至少一项 ≤ 0.5,存在明显质量问题
        0.0 - 多项严重不达标,答案不可用

        【瓶颈判定】
        - RETRIEVAL: ContextPrecision 拖后腿(检索没找对内容)
        - GENERATION: Faithfulness 或 AnswerRelevance 拖后腿(LLM 没用好上下文)
        - BOTH: 两端均显著拖后腿
        - 瓶颈必须对应实际最低分维度,不得凭印象指定

        【一致性约束】
        - overall_score 必须落在子维度均值 ±0.15 区间内(若均值非 N/A),不得自相矛盾
        - reasoning 不得简单复述子维度已有结论,应给出跨维度的整体观察
        - improvements 必须可执行(指向具体环节:重写改写/分块/重排/prompt),不得空泛("提升质量")

        【偏好防护】
        - 不因答案流畅或语气亲切就抬高综合分(style bias)
        - 子维度分数为 N/A 时,以原始上下文为唯一依据,不臆测分数

        【输出严格 JSON,不含其他文字】
        {"overall_score": 0.72, "reasoning": "≤120 字跨维度观察", "bottleneck": "RETRIEVAL", "improvements": ["≤3 条具体可执行建议"]}"""
        .formatted(
            faith,
            precision,
            answer,
            mean,
            safe(context == null ? null : context.getQuery()),
            renderChunks(context == null ? null : context.getChunks()),
            safe(context == null ? null : context.getAnswer()));
  }

  private String formatScoreOrNa(BigDecimal score) {
    if (score == null || score.signum() < 0) {
      return "N/A";
    }
    return score.stripTrailingZeros().toPlainString();
  }

  private String formatMeanOrNa(BigDecimal a, BigDecimal b, BigDecimal c) {
    if (a == null || b == null || c == null
        || a.signum() < 0 || b.signum() < 0 || c.signum() < 0) {
      return "N/A";
    }
    BigDecimal mean = a.add(b).add(c).divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
    return mean.stripTrailingZeros().toPlainString();
  }

  private String renderChunks(List<JudgeContext.RetrievedChunk> chunks) {
    if (chunks == null || chunks.isEmpty()) {
      return "无检索分块";
    }
    return chunks.stream()
        .map(
            chunk ->
                String.format(
                    "chunk_id=%d\ncontent=%s",
                    chunk.chunkId(),
                    safe(chunk.content())))
        .collect(Collectors.joining("\n"));
  }

  private String safe(String value) {
    return value == null ? "" : value;
  }
}
