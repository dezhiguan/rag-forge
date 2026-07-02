package com.ragforge.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.archive.ArchiveErrorCodes;
import com.ragforge.archive.ArchiveException;
import com.ragforge.common.ErrorMessages;
import com.ragforge.archive.ArchiveExpander;
import com.ragforge.archive.ArchiveFormat;
import com.ragforge.archive.ExpandOutcome;
import com.ragforge.archive.ExpandedEntry;
import com.ragforge.config.WorkerRoleCondition;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.dto.Identity;
import com.ragforge.model.dto.IngestCommand;
import com.ragforge.model.dto.OnConflict;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.security.OrgContextHolder;
import com.ragforge.service.ingest.IngestService;
import com.ragforge.storage.ObjectMeta;
import com.ragforge.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * 压缩包容器展开消费者（设计稿 §2）。CAS 领取容器 PENDING→EXPANDING（幂等，防 MQ 重投重复展开），从 OSS
 * 流式拉包，交 {@link ArchiveExpander} 施加护栏并逐 entry 回调登记子文档，最后写 expand_summary 并置
 * EXPANDED / FAILED。
 *
 * <p>onMessage 不加 @Transactional：CAS 领取与收尾各自即时提交；每个子文档经 {@link IngestService#register}
 * 独立事务提交（部分成功——坏 entry 跳过不失败整包，已登记子文档在致命护栏时仍保留）。
 */
@Slf4j
@Component
@Conditional(WorkerRoleCondition.class)
@RequiredArgsConstructor
@RocketMQMessageListener(
    topic = ArchiveExpandProducer.TOPIC,
    consumerGroup = "ragforge-archive-expand-group",
    maxReconsumeTimes = 3)
public class ArchiveExpandConsumer implements RocketMQListener<Long> {

  private static final String STATUS_EXPANDED = "EXPANDED";
  private static final String STATUS_FAILED = "FAILED";
  private static final String INGEST_SOURCE = "archive";

  private final DocumentMapper documentMapper;
  private final ArchiveExpander archiveExpander;
  private final ObjectStorage objectStorage;
  private final IngestService ingestService;
  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final ObjectMapper objectMapper;

  @Override
  public void onMessage(Long containerId) {
    log.info("Received archive expand message: containerId={}", containerId);
    int claimed = documentMapper.claimForExpansion(containerId);
    if (claimed == 0) {
      log.info("Skip archive expand: CAS did not claim container (already expanding/done): containerId={}", containerId);
      return;
    }
    Document container = documentMapper.selectById(containerId);
    if (container == null) {
      log.warn("Archive container vanished after claim: containerId={}", containerId);
      return;
    }

    Long orgId = resolveOrgId(container);
    try {
      if (orgId != null) {
        OrgContextHolder.set(orgId);
      }
      expand(container);
    } catch (ArchiveException ae) {
      // error_msg 面向用户展示，落中文提示（而非裸机器码），码保留在日志便于排查
      log.warn("Archive expand failed: containerId={} code={} msg={}", containerId, ae.getCode(), ae.getMessage());
      documentMapper.finishExpansion(
          containerId, STATUS_FAILED, null, ErrorMessages.toChinese(ae.getCode()));
    } catch (Exception e) {
      log.error("Archive expand errored: containerId={}", containerId, e);
      documentMapper.finishExpansion(
          containerId, STATUS_FAILED, null, ErrorMessages.toChinese(ArchiveErrorCodes.CORRUPTED));
    } finally {
      OrgContextHolder.clear();
    }
  }

  private void expand(Document container) throws Exception {
    ArchiveFormat format = ArchiveFormat.UNKNOWN;
    for (ArchiveFormat f : ArchiveFormat.values()) {
      if (f.token() != null && f.token().equals(container.getFileType())) {
        format = f;
        break;
      }
    }
    ExpandOutcome outcome;
    try (InputStream in = objectStorage.get(container.getStorageBucket(), container.getStorageKey())) {
      outcome = archiveExpander.expand(in, format, entry -> registerChild(container, entry));
    }
    documentMapper.finishExpansion(
        container.getId(), STATUS_EXPANDED, toSummaryJson(outcome), null);
    log.info(
        "Archive expanded: containerId={} total={} registered={} skipped={}",
        container.getId(),
        outcome.getTotalEntries(),
        outcome.getRegistered(),
        outcome.getSkipped().size());
  }

  /** 落 OSS + 经 IngestService 登记一个子文档（复用现有解析管道；SKIP 冲突策略做内容去重）。 */
  private void registerChild(Document container, ExpandedEntry entry) throws Exception {
    String bucket = container.getStorageBucket();
    String childKey =
        "kb_" + container.getKbId() + "/" + UUID.randomUUID() + "/" + entry.getFilename();
    String contentType = guessContentType(entry.getFilename());

    ObjectMeta meta = new ObjectMeta();
    meta.setContentType(contentType);
    meta.setSizeBytes(entry.getSize());
    meta.setUserMeta(
        Map.of("content-sha256", entry.getContentMd5(), "kb-id", String.valueOf(container.getKbId())));
    try (InputStream in = new ByteArrayInputStream(entry.getContent())) {
      objectStorage.put(bucket, childKey, in, meta);
    }

    IngestCommand cmd = new IngestCommand();
    cmd.setKbId(container.getKbId());
    cmd.setStorageBucket(bucket);
    cmd.setStorageKey(childKey);
    cmd.setFilename(entry.getFilename());
    cmd.setSizeBytes(entry.getSize());
    cmd.setContentType(contentType);
    cmd.setFileBytesMd5(entry.getContentMd5());
    Identity identity = new Identity();
    identity.setContentMd5(entry.getContentMd5());
    cmd.setIdentity(identity);
    // 压缩包内同内容文件按 md5 去重、静默跳过，不因重复而失败整包
    cmd.setOnConflict(OnConflict.SKIP);
    cmd.setIngestSource(INGEST_SOURCE);
    cmd.setParentDocumentId(container.getId());
    cmd.setArchiveEntryPath(entry.getEntryPath());
    ingestService.register(cmd);
  }

  private String toSummaryJson(ExpandOutcome outcome) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("totalEntries", outcome.getTotalEntries());
    summary.put("registered", outcome.getRegistered());
    summary.put("skipped", outcome.getSkipped());
    try {
      return objectMapper.writeValueAsString(summary);
    } catch (Exception e) {
      log.warn("Failed to serialize expand summary", e);
      return null;
    }
  }

  private Long resolveOrgId(Document doc) {
    if (doc == null || doc.getKbId() == null) {
      return null;
    }
    KnowledgeBase kb = knowledgeBaseMapper.selectById(doc.getKbId());
    return kb == null ? null : kb.getOrgId();
  }

  /** 按扩展名推断 contentType（白名单内的文档类型），供下游解析管道路由。 */
  private static String guessContentType(String filename) {
    String ext = "";
    if (filename != null) {
      int dot = filename.lastIndexOf('.');
      if (dot >= 0 && dot < filename.length() - 1) {
        ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
      }
    }
    return switch (ext) {
      case "pdf" -> "application/pdf";
      case "doc" -> "application/msword";
      case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
      case "md", "markdown" -> "text/markdown";
      case "html", "htm" -> "text/html";
      default -> "application/octet-stream";
    };
  }
}
