package com.ragforge.mq;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArchiveExpandProducerTest {

  @Mock private RocketMQTemplate rocketMQTemplate;
  @Mock private ObjectProvider<ArchiveExpandConsumer> consumerProvider;
  @Mock private Environment environment;
  @Mock private ThreadPoolTaskExecutor executor;

  private ArchiveExpandProducer producer;

  @BeforeEach
  void setUp() {
    producer =
        new ArchiveExpandProducer(rocketMQTemplate, consumerProvider, environment, executor);
  }

  @Test
  void mqMode_sendsToRocketMq() {
    ReflectionTestUtils.setField(producer, "dispatchMode", "mq");
    producer.send(10L);
    verify(rocketMQTemplate).convertAndSend(ArchiveExpandProducer.TOPIC, 10L);
  }

  @Test
  void inlineMode_dispatchesToConsumerViaExecutor() {
    ReflectionTestUtils.setField(producer, "dispatchMode", "inline");
    when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});
    ArchiveExpandConsumer consumer = org.mockito.Mockito.mock(ArchiveExpandConsumer.class);
    when(consumerProvider.getIfAvailable()).thenReturn(consumer);
    // 同步执行提交的任务，便于断言内联派发
    doAnswer(
            inv -> {
              ((Runnable) inv.getArgument(0)).run();
              return null;
            })
        .when(executor)
        .execute(any());

    producer.send(20L);

    verify(consumer).onMessage(20L);
    verify(rocketMQTemplate, never()).convertAndSend(eq(ArchiveExpandProducer.TOPIC), any(Object.class));
  }

  @Test
  void inlineMode_forbiddenInProd() {
    ReflectionTestUtils.setField(producer, "dispatchMode", "inline");
    when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> producer.send(30L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("INLINE_DISPATCH_FORBIDDEN_IN_PROD");
  }
}
