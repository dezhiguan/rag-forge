package com.ragforge.mq;

import com.ragforge.mapper.DocumentMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.pipeline.DocumentPipelineService;
import com.ragforge.pipeline.image.ImagePipelineService;
import java.util.Locale;
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

  private final DocumentMapper documentMapper;
  private final DocumentPipelineService pipelineService;
  private final ImagePipelineService imagePipelineService;

  @Override
  public void onMessage(Long documentId) {
    log.info("Received document process message: docId={}", documentId);
    int claimed = documentMapper.markProcessingIfRunnable(documentId);
    if (claimed == 0) {
      log.info("Skip document process message because CAS guard did not claim doc: docId={}", documentId);
      return;
    }
    Document doc = documentMapper.selectById(documentId);
    if (doc != null && normalizeContentType(doc.getFileType()).startsWith("image/")) {
      imagePipelineService.processImageDocument(documentId);
      return;
    }
    pipelineService.processDocument(documentId);
  }

  private static String normalizeContentType(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return "";
    }
    int semicolon = contentType.indexOf(';');
    String normalized = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
    return normalized.trim().toLowerCase(Locale.ROOT);
  }
}
