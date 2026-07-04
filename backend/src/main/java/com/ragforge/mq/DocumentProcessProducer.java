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
    // 关键：若处在事务中，必须等事务提交后再发消息。否则消费者可能在 documents 行提交可见前
    // 就消费到消息，markProcessingIfRunnable 的 CAS 更新 0 行 → 被跳过 → 文档永久卡 PENDING。
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isSynchronizationActive()) {
      org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
          new org.springframework.transaction.support.TransactionSynchronization() {
            @Override
            public void afterCommit() {
              dispatch(documentId);
            }
          });
      return;
    }
    dispatch(documentId);
  }

  private void dispatch(Long documentId) {
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
