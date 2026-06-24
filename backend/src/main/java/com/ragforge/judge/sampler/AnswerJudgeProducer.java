package com.ragforge.judge.sampler;

import com.ragforge.judge.JudgeOrchestrator;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.ObjectProvider;
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
  private final ObjectProvider<JudgeOrchestrator> judgeOrchestrator;

  @Value("${ragforge.judge.dispatch-mode:mq}")
  private String dispatchMode;

  public void publishJudgeRequest(AnswerJudgeMessage msg, SampleRequest req) {
    if ("disabled".equalsIgnoreCase(dispatchMode)) {
      return;
    }
    if ("inline".equalsIgnoreCase(dispatchMode)
        && Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
      log.error("INLINE_JUDGE_DISPATCH_FORBIDDEN_IN_PROD");
      return;
    }
    SampleDecision decision = sampler.decide(req);
    if (!decision.keep()) {
      log.debug("Judge skipped: answerLogId={}, reason={}", msg.getAnswerLogId(), decision.reason());
      return;
    }
    if ("inline".equalsIgnoreCase(dispatchMode)) {
      log.warn("INLINE_JUDGE_DISPATCH: answerLogId={}", msg.getAnswerLogId());
      JudgeOrchestrator orchestrator = judgeOrchestrator.getIfAvailable();
      if (orchestrator == null) {
        log.error(
            "Inline judge requires ragforge.role=judge or ragforge.role=all, answerLogId={}",
            msg.getAnswerLogId());
        return;
      }
      orchestrator.judge(msg);
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

