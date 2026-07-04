package com.ragforge.mq;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class DocumentProcessProducerTest {

  @Test
  void send_inlineModeInProd_forbidden() {
    RocketMQTemplate rocketMQTemplate = org.mockito.Mockito.mock(RocketMQTemplate.class);
    DocumentProcessConsumer consumer = org.mockito.Mockito.mock(DocumentProcessConsumer.class);
    ObjectProvider<DocumentProcessConsumer> provider = org.mockito.Mockito.mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(consumer);
    Environment environment = org.mockito.Mockito.mock(Environment.class);
    when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
    DocumentProcessProducer producer = new DocumentProcessProducer(
        rocketMQTemplate, provider, environment, new ThreadPoolTaskExecutor());

    ReflectionTestUtils.setField(producer, "dispatchMode", "inline");

    assertThrows(IllegalStateException.class, () -> producer.send(42L));
    verify(rocketMQTemplate, never()).convertAndSend(DocumentProcessProducer.TOPIC, 42L);
    verify(consumer, never()).onMessage(42L);
  }

  @Test
  void send_mqMode_publishesRocketMqMessage() {
    RocketMQTemplate rocketMQTemplate = org.mockito.Mockito.mock(RocketMQTemplate.class);
    DocumentProcessConsumer consumer = org.mockito.Mockito.mock(DocumentProcessConsumer.class);
    ObjectProvider<DocumentProcessConsumer> provider = org.mockito.Mockito.mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(consumer);
    Environment environment = org.mockito.Mockito.mock(Environment.class);
    when(environment.getActiveProfiles()).thenReturn(new String[] {});
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.initialize();
    DocumentProcessProducer producer = new DocumentProcessProducer(
        rocketMQTemplate, provider, environment, executor);
    ReflectionTestUtils.setField(producer, "dispatchMode", "mq");

    producer.send(42L);

    verify(rocketMQTemplate).convertAndSend(DocumentProcessProducer.TOPIC, 42L);
    verify(consumer, never()).onMessage(42L);
  }

  @Test
  void send_inTransaction_defersDispatchUntilAfterCommit() {
    RocketMQTemplate rocketMQTemplate = org.mockito.Mockito.mock(RocketMQTemplate.class);
    ObjectProvider<DocumentProcessConsumer> provider = org.mockito.Mockito.mock(ObjectProvider.class);
    Environment environment = org.mockito.Mockito.mock(Environment.class);
    when(environment.getActiveProfiles()).thenReturn(new String[] {});
    DocumentProcessProducer producer =
        new DocumentProcessProducer(rocketMQTemplate, provider, environment, new ThreadPoolTaskExecutor());
    ReflectionTestUtils.setField(producer, "dispatchMode", "mq");

    org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
    try {
      producer.send(42L);
      // 事务未提交前：不应发消息（否则消费者可能早于 documents 行提交而 CAS 跳过）
      verify(rocketMQTemplate, never()).convertAndSend(DocumentProcessProducer.TOPIC, 42L);

      // 触发提交后回调
      var syncs =
          org.springframework.transaction.support.TransactionSynchronizationManager
              .getSynchronizations();
      org.junit.jupiter.api.Assertions.assertEquals(1, syncs.size());
      syncs.forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);

      verify(rocketMQTemplate).convertAndSend(DocumentProcessProducer.TOPIC, 42L);
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void send_inlineMode_processesWithoutRocketMq() {
    RocketMQTemplate rocketMQTemplate = org.mockito.Mockito.mock(RocketMQTemplate.class);
    DocumentProcessConsumer consumer = org.mockito.Mockito.mock(DocumentProcessConsumer.class);
    ObjectProvider<DocumentProcessConsumer> provider = org.mockito.Mockito.mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(consumer);
    Environment environment = org.mockito.Mockito.mock(Environment.class);
    when(environment.getActiveProfiles()).thenReturn(new String[] {});
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.initialize();
    DocumentProcessProducer producer = new DocumentProcessProducer(
        rocketMQTemplate, provider, environment, executor);
    ReflectionTestUtils.setField(producer, "dispatchMode", "inline");

    producer.send(42L);

    verify(consumer, timeout(1000)).onMessage(42L);
    verify(rocketMQTemplate, never()).convertAndSend(DocumentProcessProducer.TOPIC, 42L);
  }
}
