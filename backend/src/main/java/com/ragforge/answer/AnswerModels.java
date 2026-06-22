package com.ragforge.answer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ragforge.model.vo.SearchResponse;
import java.util.List;
import lombok.Data;

public final class AnswerModels {
  private AnswerModels() {}

  @Data
  public static class AnswerRequest {
    private List<Long> kbIds;
    private String query;
    private String retrievalStrategy = "hybrid";
    private String answerMode = "ON";
    private boolean stream = true;
    private int topK = 10;
    private int maxTokens = 800;
    private String judgeSource = "PRODUCTION";
    private Boolean forceSample = false;
    private Long goldenQuestionId;
  }

  @Data
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class AnswerResponse {
    private String answer;
    private List<Citation> citations;
    private SearchResponse retrieval;
    private TokenUsage tokens;
    private Latency latency;
    private String guardRailResult;
    private String llmModel;
  }

  @Data
  public static class Citation {
    private int id;
    private Long chunkId;
    private Long docId;
    private String modality;
    private String textSnippet;
    private String imageUrl;
  }

  public record TokenUsage(int prompt, int completion) {}

  public record Latency(long retrieval, long llm, long total) {}

  public enum GuardRailResult {
    PASS,
    NO_CITATIONS,
    OUT_OF_SCOPE,
    PII_LEAK
  }

  record AnswerRun(
      AnswerResponse response,
      String answerMode,
      String llmModel,
      String retrievalStrategy,
      long retrievalLatencyMs,
      long llmLatencyMs,
      long totalLatencyMs) {}
}
