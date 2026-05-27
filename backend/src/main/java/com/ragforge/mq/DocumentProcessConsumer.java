package com.ragforge.mq;

import com.ragforge.pipeline.DocumentPipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
    topic = DocumentProcessProducer.TOPIC,
    consumerGroup = "ragforge-doc-process-group",
    maxReconsumeTimes = 3)
public class DocumentProcessConsumer implements RocketMQListener<Long> {

  private final DocumentPipelineService pipelineService;

  @Override
  public void onMessage(Long documentId) {
    log.info("Received document process message: docId={}", documentId);
    pipelineService.processDocument(documentId);
  }
}
