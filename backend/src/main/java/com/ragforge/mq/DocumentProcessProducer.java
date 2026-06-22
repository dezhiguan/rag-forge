package com.ragforge.mq;

import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProcessProducer {

  public static final String TOPIC = "ragforge-document-process";

  private final RocketMQTemplate rocketMQTemplate;
  private final ObjectProvider<DocumentProcessConsumer> documentProcessConsumer;

  @Value("${ragforge.document-processing.dispatch-mode:mq}")
  private String dispatchMode;

  public void send(Long documentId) {
    if ("inline".equalsIgnoreCase(dispatchMode)) {
      log.info("Dispatching document process inline without RocketMQ: docId={}", documentId);
      CompletableFuture.runAsync(() -> processInline(documentId));
      return;
    }
    rocketMQTemplate.convertAndSend(TOPIC, documentId);
    log.info("Sent document process message: docId={}", documentId);
  }

  private void processInline(Long documentId) {
    try {
      DocumentProcessConsumer consumer = documentProcessConsumer.getIfAvailable();
      if (consumer == null) {
        throw new IllegalStateException(
            "Inline document processing requires ragforge.role=worker or ragforge.role=all");
      }
      consumer.onMessage(documentId);
    } catch (Exception e) {
      log.error("Inline document process failed: docId={}", documentId, e);
    }
  }
}
