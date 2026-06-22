package com.ragforge.mq;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

class DocumentProcessProducerTest {

  @Test
  void send_mqMode_publishesRocketMqMessage() {
    RocketMQTemplate rocketMQTemplate = org.mockito.Mockito.mock(RocketMQTemplate.class);
    DocumentProcessConsumer consumer = org.mockito.Mockito.mock(DocumentProcessConsumer.class);
    ObjectProvider<DocumentProcessConsumer> provider = org.mockito.Mockito.mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(consumer);
    DocumentProcessProducer producer = new DocumentProcessProducer(rocketMQTemplate, provider);
    ReflectionTestUtils.setField(producer, "dispatchMode", "mq");

    producer.send(42L);

    verify(rocketMQTemplate).convertAndSend(DocumentProcessProducer.TOPIC, 42L);
    verify(consumer, never()).onMessage(42L);
  }

  @Test
  void send_inlineMode_processesWithoutRocketMq() {
    RocketMQTemplate rocketMQTemplate = org.mockito.Mockito.mock(RocketMQTemplate.class);
    DocumentProcessConsumer consumer = org.mockito.Mockito.mock(DocumentProcessConsumer.class);
    ObjectProvider<DocumentProcessConsumer> provider = org.mockito.Mockito.mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(consumer);
    DocumentProcessProducer producer = new DocumentProcessProducer(rocketMQTemplate, provider);
    ReflectionTestUtils.setField(producer, "dispatchMode", "inline");

    producer.send(42L);

    verify(consumer, timeout(1000)).onMessage(42L);
    verify(rocketMQTemplate, never()).convertAndSend(DocumentProcessProducer.TOPIC, 42L);
  }
}
