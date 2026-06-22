package com.ragforge.judge;

import com.ragforge.config.JudgeRoleCondition;
import com.ragforge.judge.sampler.AnswerJudgeMessage;
import com.ragforge.judge.sampler.AnswerJudgeProducer;
import com.ragforge.metrics.RagforgeMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Conditional(JudgeRoleCondition.class)
@RequiredArgsConstructor
@RocketMQMessageListener(
    topic = AnswerJudgeProducer.TOPIC,
    consumerGroup = "ragforge-judge-consumer",
    maxReconsumeTimes = 3)
public class AnswerJudgeConsumer implements RocketMQListener<AnswerJudgeMessage> {

  private final JudgeOrchestrator orchestrator;
  private final RagforgeMetrics metrics;

  @Override
  public void onMessage(AnswerJudgeMessage msg) {
    long start = System.nanoTime();
    String source = msg == null || msg.getSource() == null ? "UNKNOWN" : msg.getSource();
    metrics.recordJudgeRequests(source);
    try {
      orchestrator.judge(msg);
      metrics.recordJudgeDuration(source, System.nanoTime() - start);
    } catch (RuntimeException e) {
      metrics.recordJudgeFailed(source, e.getClass().getSimpleName());
      log.error("Judge failed: answerLogId={}", msg == null ? "null" : msg.getAnswerLogId(), e);
      throw e;
    }
  }
}
