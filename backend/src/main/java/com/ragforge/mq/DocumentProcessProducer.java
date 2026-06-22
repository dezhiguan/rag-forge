package com.ragforge.mq;

import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProcessProducer {

  public static final String TOPIC = "ragforge-document-process";

  private final RocketMQTemplate rocketMQTemplate;
  private final ObjectProvider<DocumentProcessConsumer> documentProcessConsumer;
  private final Environment environment;
  private final ThreadPoolTaskExecutor documentProcessExecutor;

  @Value("${ragforge.document-processing.dispatch-mode:mq}")
  private String dispatchMode;

  public void send(Long documentId) {
    if ("inline".equalsIgnoreCase(dispatchMode)) {
      if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
        throw new IllegalStateException("INLINE_DISPATCH_FORBIDDEN_IN_PROD");
      }
      log.warn("INLINE_DISPATCH_ACTIVE: bypassing RocketMQ, docId={}", documentId);
      documentProcessExecutor.execute(() -> processInline(documentId));
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
