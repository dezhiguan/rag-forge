package com.ragforge.judge;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class JudgePromptLibrary {

  public String systemFor(ScoreDimension dimension) {
    return switch (dimension) {
      case FAITHFULNESS -> "你是一个严格的 RAG 评测专家。";
      case CONTEXT_PRECISION, ANSWER_RELEVANCE -> "你是一个严格的 RAG 评测专家。";
      case COMPOSITE -> "你是一个严格的 RAG 评测专家。";
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
    return "你的任务：判断生成答案是否仅基于检索内容，无幻觉。\n\n"
        + "【输入】\n"
        + "Query: "
        + safe(context == null ? null : context.getQuery())
        + "\nRetrieved Context:\n"
        + renderChunks(context == null ? null : context.getChunks())
        + "\nGenerated Answer: "
        + safe(context == null ? null : context.getAnswer())
        + "\n\n"
        + "【评分标准】\n"
        + "1.0 - 答案完全基于上下文，每个事实都有出处\n"
        + "0.7 - 大部分基于但有 1 处推测\n"
        + "0.4 - 部分基于上下文，部分是模型自己编的\n"
        + "0.0 - 答案与上下文无关或事实错误\n\n"
        + "【禁止偏好】\n"
        + "- 不要因答案啰嗦给高分（length bias）\n"
        + "- 不要因风格亲和给高分（style bias）\n"
        + "- 短答案不等于忠实度低\n\n"
        + "【输出严格 JSON】\n"
        + "{\"score\": 0.85, \"reasoning\": \"≤80 字说明\", \"hallucinated_claims\": []}";
  }

  private String contextPrecisionPrompt(JudgeContext context) {
    return "你的任务：判断检索 top-K chunks 里多少是真相关的。\n\n"
        + "对每个 chunk 标 relevant=true/false，输出整体精度分。\n\n"
        + "Query: "
        + safe(context == null ? null : context.getQuery())
        + "\nRetrieved Context:\n"
        + renderChunks(context == null ? null : context.getChunks())
        + "\nGenerated Answer: "
        + safe(context == null ? null : context.getAnswer())
        + "\n\n"
        + "输出 JSON:\n"
        + "{\"score\": 0.60, \"reasoning\": \"≤80 字\", \"chunk_relevance\": [{\"chunk_id\": 123, \"relevant\": true}, {\"chunk_id\": 456, \"relevant\": false}]}";
  }

  private String answerRelevancePrompt(JudgeContext context) {
    return "你的任务：判断答案是否回应了 query（不偏题、不答非所问）。\n\n"
        + "Query: "
        + safe(context == null ? null : context.getQuery())
        + "\nRetrieved Context:\n"
        + renderChunks(context == null ? null : context.getChunks())
        + "\nGenerated Answer: "
        + safe(context == null ? null : context.getAnswer())
        + "\n\n"
        + "输出 JSON:\n"
        + "{\"score\": 0.75, \"reasoning\": \"≤80 字\", \"off_topic_parts\": [\"列出离题段落，没有则为空数组\"]}";
  }

  private String compositePrompt(
      JudgeContext context, BigDecimal faithfulness, BigDecimal contextPrecision, BigDecimal answerRelevance) {
    String faith =
        faithfulness == null || faithfulness.signum() < 0 ? "N/A" : faithfulness.stripTrailingZeros().toPlainString();
    String precision =
        contextPrecision == null || contextPrecision.signum() < 0
            ? "N/A"
            : contextPrecision.stripTrailingZeros().toPlainString();
    String answer =
        answerRelevance == null || answerRelevance.signum() < 0
            ? "N/A"
            : answerRelevance.stripTrailingZeros().toPlainString();

    return "你已知前三项分数："
        + faith
        + ", "
        + precision
        + ", "
        + answer
        + "\n\n"
        + "你的任务：基于检索内容 + 答案，给综合判断 + 改进建议。\n\n"
        + "Query: "
        + safe(context == null ? null : context.getQuery())
        + "\nRetrieved Context:\n"
        + renderChunks(context == null ? null : context.getChunks())
        + "\nGenerated Answer: "
        + safe(context == null ? null : context.getAnswer())
        + "\n\n"
        + "输出 JSON:\n"
        + "{\"overall_score\": 0.72, \"reasoning\": \"≤120 字\", \"bottleneck\": \"RETRIEVAL / GENERATION / BOTH\", \"improvements\": [\"≤3 条具体建议\"]}";
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
