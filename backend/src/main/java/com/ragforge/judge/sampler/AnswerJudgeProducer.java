package com.ragforge.judge.sampler;

import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerJudgeProducer {

  public static final String TOPIC = "ragforge-answer-judge";

  private final RocketMQTemplate rocketMQTemplate;
  private final JudgeSampler sampler;
  private final Environment environment;

  @Value("${ragforge.judge.dispatch-mode:mq}")
  private String dispatchMode;

  public void publishJudgeRequest(AnswerJudgeMessage msg, SampleRequest req) {
    if ("disabled".equalsIgnoreCase(dispatchMode)) {
      return;
    }
    if ("inline".equalsIgnoreCase(dispatchMode)
        && Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
      log.error("INLINE_JUDGE_DISPATCH_FORBIDDEN_IN_PROD");
      throw new IllegalStateException("INLINE_DISPATCH_FORBIDDEN_IN_PROD");
    }
    SampleDecision decision = sampler.decide(req);
    if (!decision.keep()) {
      log.debug("Judge skipped: answerLogId={}, reason={}", msg.getAnswerLogId(), decision.reason());
      return;
    }
    if ("inline".equalsIgnoreCase(dispatchMode)) {
      log.warn("INLINE_JUDGE_DISPATCH: answerLogId={}", msg.getAnswerLogId());
      return;
    }
    try {
      rocketMQTemplate.convertAndSend(TOPIC, msg);
      log.info("Sent judge request: answerLogId={}, sampleRate={}", msg.getAnswerLogId(),
          decision.effectiveSampleRate());
    } catch (Exception e) {
      log.error("Failed to send judge request: answerLogId={}", msg.getAnswerLogId(), e);
    }
  }
}

