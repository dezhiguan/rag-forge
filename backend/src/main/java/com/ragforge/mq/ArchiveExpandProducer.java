package com.ragforge.mq;

import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 压缩包容器展开消息生产者。消息体仅为容器 documentId（Long），Consumer 据此从 OSS 拉包解压。 结构与
 * {@link DocumentProcessProducer} 一致：默认走 RocketMQ；{@code inline} 模式（dev / 单机）绕过 broker
 * 走本地线程池直调 Consumer，prod 禁用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArchiveExpandProducer {

  public static final String TOPIC = "ragforge-archive-expand";

  private final RocketMQTemplate rocketMQTemplate;
  private final ObjectProvider<ArchiveExpandConsumer> archiveExpandConsumer;
  private final Environment environment;
  private final ThreadPoolTaskExecutor documentProcessExecutor;

  @Value("${ragforge.document-processing.dispatch-mode:mq}")
  private String dispatchMode;

  public void send(Long containerDocumentId) {
    if ("inline".equalsIgnoreCase(dispatchMode)) {
      if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
        throw new IllegalStateException("INLINE_DISPATCH_FORBIDDEN_IN_PROD");
      }
      log.warn("INLINE_DISPATCH_ACTIVE: bypassing RocketMQ, archive containerId={}", containerDocumentId);
      documentProcessExecutor.execute(() -> processInline(containerDocumentId));
      return;
    }
    rocketMQTemplate.convertAndSend(TOPIC, containerDocumentId);
    log.info("Sent archive expand message: containerId={}", containerDocumentId);
  }

  private void processInline(Long containerDocumentId) {
    try {
      ArchiveExpandConsumer consumer = archiveExpandConsumer.getIfAvailable();
      if (consumer == null) {
        throw new IllegalStateException(
            "Inline archive expand requires ragforge.role=worker or ragforge.role=all");
      }
      consumer.onMessage(containerDocumentId);
    } catch (Exception e) {
      log.error("Inline archive expand failed: containerId={}", containerDocumentId, e);
    }
  }
}
