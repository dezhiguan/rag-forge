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

  public record RetrievedChunk(Long chunkId, String content, BigDecimal score) {}
}
