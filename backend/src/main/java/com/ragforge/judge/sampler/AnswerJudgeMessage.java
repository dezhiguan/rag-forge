package com.ragforge.judge.sampler;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class AnswerJudgeMessage {
  private Long answerLogId;
  private String source;
  private Long goldenQuestionId;
  private String forceSample;
  private LocalDateTime requestedAt;
}

