package com.ragforge.judge.sampler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import com.ragforge.judge.JudgeOrchestrator;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AnswerJudgeProducerTest {

  @Test
  void publish_disabledModeSkipsAll() {
    RocketMQTemplate rocketMQTemplate = org.mockito.Mockito.mock(RocketMQTemplate.class);
    JudgeSampler sampler = org.mockito.Mockito.mock(JudgeSampler.class);
    Environment environment = org.mockito.Mockito.mock(Environment.class);

    AnswerJudgeProducer producer = new AnswerJudgeProducer(rocketMQTemplate, sampler, environment, orchestratorProvider());
    ReflectionTestUtils.setField(producer, "dispatchMode", "disabled");

    producer.publishJudgeRequest(sampleMessage(123L), sampleRequest(123L));

    verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(AnswerJudgeMessage.class));
    verify(sampler, never()).decide(any());
  }

  @Test
  void publish_inlineModeBypassesMqAndRunsOrchestrator() {
    RocketMQTemplate rocketMQTemplate = org.mockito.Mockito.mock(RocketMQTemplate.class);
    JudgeSampler sampler = org.mockito.Mockito.mock(JudgeSampler.class);
    Environment environment = org.mockito.Mockito.mock(Environment.class);
    JudgeOrchestrator orchestrator = org.mockito.Mockito.mock(JudgeOrchestrator.class);
    org.springframework.beans.factory.ObjectProvider<JudgeOrchestrator> orchestratorProvider =
        org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
    when(environment.getActiveProfiles()).thenReturn(new String[] {});
    when(orchestratorProvider.getIfAvailable()).thenReturn(orchestrator);

    AnswerJudgeProducer producer =
        new AnswerJudgeProducer(rocketMQTemplate, sampler, environment, orchestratorProvider);
    ReflectionTestUtils.setField(producer, "dispatchMode", "inline");
    when(sampler.decide(argThat(req -> req.answerLogId() == 123L && req.forceSample() == false)))
        .thenReturn(new SampleDecision(true, 1L, null, "KEEP_BY_RATE"));

    producer.publishJudgeRequest(sampleMessage(123L), sampleRequest(123L));

    verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(AnswerJudgeMessage.class));
    verify(sampler).decide(any());
    org.mockito.Mockito.verify(orchestrator).judge(any());
  }

  @Test
  void publish_mqModeSwallowsSendFailure() {
    RocketMQTemplate rocketMQTemplate = org.mockito.Mockito.mock(RocketMQTemplate.class);
    JudgeSampler sampler = org.mockito.Mockito.mock(JudgeSampler.class);
    Environment environment = org.mockito.Mockito.mock(Environment.class);
    org.mockito.Mockito.doThrow(new RuntimeException("mq down"))
        .when(rocketMQTemplate)
        .convertAndSend(eq(AnswerJudgeProducer.TOPIC), any(AnswerJudgeMessage.class));
    when(sampler.decide(argThat(req -> req.answerLogId() == 123L && req.forceSample() == false)))
        .thenReturn(new SampleDecision(true, 1L, null, "KEEP_BY_RATE"));

    AnswerJudgeProducer producer = new AnswerJudgeProducer(rocketMQTemplate, sampler, environment, orchestratorProvider());
    ReflectionTestUtils.setField(producer, "dispatchMode", "mq");

    producer.publishJudgeRequest(sampleMessage(123L), sampleRequest(123L));
    verify(rocketMQTemplate).convertAndSend(eq(AnswerJudgeProducer.TOPIC), any(AnswerJudgeMessage.class));
    verify(sampler).decide(any());
  }

  @Test
  void send_inlineModeInProdLogsAndSkipsBecauseStartupGuardOwnsFailFast() {
    RocketMQTemplate rocketMQTemplate = org.mockito.Mockito.mock(RocketMQTemplate.class);
    JudgeSampler sampler = org.mockito.Mockito.mock(JudgeSampler.class);
    Environment environment = org.mockito.Mockito.mock(Environment.class);
    when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});

    AnswerJudgeProducer producer = new AnswerJudgeProducer(rocketMQTemplate, sampler, environment, orchestratorProvider());
    ReflectionTestUtils.setField(producer, "dispatchMode", "inline");

    producer.publishJudgeRequest(sampleMessage(123L), sampleRequest(123L));

    verify(rocketMQTemplate, never()).convertAndSend(anyString(), any(AnswerJudgeMessage.class));
    verify(sampler, never()).decide(any());
  }

  @Test
  void guard_inlineModeInProdFailsFastAtStartup() {
    Environment environment = org.mockito.Mockito.mock(Environment.class);
    when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
    JudgeDispatchModeGuard guard = new JudgeDispatchModeGuard(environment);
    ReflectionTestUtils.setField(guard, "dispatchMode", "inline");

    assertThrows(IllegalStateException.class, () -> guard.run(null));
  }

  private static AnswerJudgeMessage sampleMessage(long id) {
    AnswerJudgeMessage msg = new AnswerJudgeMessage();
    msg.setAnswerLogId(id);
    msg.setSource("PRODUCTION");
    msg.setForceSample("AUTO");
    msg.setRequestedAt(LocalDateTime.now());
    return msg;
  }

  private static SampleRequest sampleRequest(long id) {
    return new SampleRequest(id, new Long[] {10L}, "tenant-a", "PRODUCTION", false);
  }

  @SuppressWarnings("unchecked")
  private static org.springframework.beans.factory.ObjectProvider<JudgeOrchestrator> orchestratorProvider() {
    return org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
  }
}
