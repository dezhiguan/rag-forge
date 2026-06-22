package com.ragforge.judge;

import com.ragforge.answer.AnswerModels.Citation;
import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

@Data
public class JudgeContext {

  private String query;
  private List<RetrievedChunk> chunks;
  private String answer;
  private List<Citation> citations;
  private String expectedAnswer;
  private Long[] expectedChunkIds;
  private BigDecimal faithfulnessScore;
  private BigDecimal contextPrecisionScore;
  private BigDecimal answerRelevanceScore;

  public record RetrievedChunk(Long chunkId, String content, BigDecimal score) {}

  public JudgeContext withPriorScores(
      BigDecimal faithfulnessScore, BigDecimal contextPrecisionScore, BigDecimal answerRelevanceScore) {
    JudgeContext copy = new JudgeContext();
    copy.setQuery(query);
    copy.setChunks(chunks);
    copy.setAnswer(answer);
    copy.setCitations(citations);
    copy.setExpectedAnswer(expectedAnswer);
    copy.setExpectedChunkIds(expectedChunkIds);
    copy.setFaithfulnessScore(faithfulnessScore);
    copy.setContextPrecisionScore(contextPrecisionScore);
    copy.setAnswerRelevanceScore(answerRelevanceScore);
    return copy;
  }
}
