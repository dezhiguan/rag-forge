package com.ragforge.mq;

import com.ragforge.mapper.DocumentMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.config.WorkerRoleCondition;
import com.ragforge.pipeline.DocumentPipelineService;
import com.ragforge.pipeline.image.ImagePipelineService;
import com.ragforge.metrics.RagforgeMetrics;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Conditional(WorkerRoleCondition.class)
@RequiredArgsConstructor
@RocketMQMessageListener(
    topic = DocumentProcessProducer.TOPIC,
    consumerGroup = "ragforge-doc-process-group",
    maxReconsumeTimes = 3)
public class DocumentProcessConsumer implements RocketMQListener<Long> {

  private final DocumentMapper documentMapper;
  private final DocumentPipelineService pipelineService;
  private final ImagePipelineService imagePipelineService;
  private final RagforgeMetrics metrics;
  private final com.ragforge.mapper.KnowledgeBaseMapper knowledgeBaseMapper;

  @Override
  public void onMessage(Long documentId) {
    log.info("Received document process message: docId={}", documentId);
    int claimed = documentMapper.markProcessingIfRunnable(documentId);
    if (claimed == 0) {
      log.info("Skip document process message because CAS guard did not claim doc: docId={}", documentId);
      return;
    }
    Document doc = documentMapper.selectById(documentId);
    // 入库链路无请求上下文：按文档所属库的组织设上下文，使 embedding/OCR 成本归该组织。
    Long orgId = resolveOrgId(doc);
    try {
      if (orgId != null) {
        com.ragforge.security.OrgContextHolder.set(orgId);
      }
      if (doc != null && normalizeContentType(doc.getFileType()).startsWith("image/")) {
        processWithMetrics("image", documentId, () -> imagePipelineService.processImageDocument(documentId));
      } else {
        processWithMetrics("text", documentId, () -> pipelineService.processDocument(documentId));
      }
    } finally {
      com.ragforge.security.OrgContextHolder.clear();
    }
  }

  private Long resolveOrgId(Document doc) {
    if (doc == null || doc.getKbId() == null) {
      return null;
    }
    com.ragforge.model.entity.KnowledgeBase kb = knowledgeBaseMapper.selectById(doc.getKbId());
    return kb == null ? null : kb.getOrgId();
  }

  private void processWithMetrics(String modality, Long documentId, Runnable action) {
    long start = System.nanoTime();
    try {
      action.run();
      metrics.recordWorkerDuration(modality, System.nanoTime() - start);
    } catch (RuntimeException e) {
      metrics.recordWorkerDuration(modality, System.nanoTime() - start);
      metrics.recordWorkerFailed(reason(e));
      throw e;
    }
  }

  private static String normalizeContentType(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return "";
    }
    int semicolon = contentType.indexOf(';');
    String normalized = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
    return normalized.trim().toLowerCase(Locale.ROOT);
  }

  private static String reason(RuntimeException e) {
    String simpleName = e.getClass().getSimpleName();
    return simpleName == null || simpleName.isBlank() ? "RuntimeException" : simpleName;
  }
}
