package com.ragforge.service.ingest;

import com.ragforge.common.BizException;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.metrics.RagforgeMetrics;
import com.ragforge.model.dto.Identity;
import com.ragforge.model.dto.IngestCommand;
import com.ragforge.model.dto.IngestResult;
import com.ragforge.model.dto.OnConflict;
import com.ragforge.model.entity.Document;
import com.ragforge.mq.ArchiveExpandProducer;
import com.ragforge.mq.DocumentProcessProducer;
import com.ragforge.pipeline.indexer.EsIndexService;
import com.ragforge.storage.ObjectStorage;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestServiceImpl implements IngestService {

  // 专用低优先级线程池给 afterCommit 派发 MQ；避免 RocketMQ broker 抖动反吃 HTTP 请求线程，
  // 直接导致 /documents/register 在云上 120s 超时（acc-11 复现的回归）。
  private static final TaskExecutor MQ_DISPATCH_EXECUTOR = buildMqDispatchExecutor();

  private static TaskExecutor buildMqDispatchExecutor() {
    ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
    exec.setCorePoolSize(2);
    exec.setMaxPoolSize(8);
    exec.setQueueCapacity(256);
    exec.setThreadNamePrefix("ingest-mq-dispatch-");
    exec.setWaitForTasksToCompleteOnShutdown(true);
    exec.setAwaitTerminationSeconds(5);
    exec.initialize();
    return exec;
  }

  private static final String STATUS_PENDING = "PENDING";
  private static final String STATUS_REPROCESSING = "REPROCESSING";

  private final DocumentMapper documentMapper;
  private final DocumentChunkMapper chunkMapper;
  private final DocumentProcessProducer mqProducer;
  private final ArchiveExpandProducer archiveExpandProducer;
  private final EsIndexService esIndexService;
  private final ObjectStorage objectStorage;
  private final RagforgeMetrics metrics;

  @Override
  @Transactional
  public IngestResult register(IngestCommand cmd) {
    validate(cmd);

    Document existing = resolveByIdentity(cmd.getKbId(), cmd.getIdentity());
    if (existing == null) {
      return doCreate(cmd);
    }

    boolean md5Same =
        Objects.equals(existing.getContentMd5(), cmd.getIdentity().getContentMd5());
    OnConflict onConflict = cmd.getOnConflict() == null ? OnConflict.REJECT : cmd.getOnConflict();

    return switch (onConflict) {
      case REJECT ->
          throw new BizException(
              409, "DOC_IDENTITY_CONFLICT", Map.of("existingDocId", existing.getId()));
      case SKIP -> {
        if (!md5Same) {
          throw new BizException(
              409,
              "DOC_CONTENT_CHANGED_USE_REPLACE",
              Map.of("existingDocId", existing.getId()));
        }
        metrics.recordIngestSkipped();
        yield IngestResult.skipped(existing.getId());
      }
      case REPLACE -> {
        if (md5Same) {
          metrics.recordIngestSkipped();
          yield IngestResult.skipped(existing.getId());
        }
        yield doReplace(existing, cmd);
      }
    };
  }

  private Document resolveByIdentity(Long kbId, Identity id) {
    if (StringUtils.hasText(id.getExternalId())) {
      Document byExternalId = documentMapper.selectByExternalIdForUpdate(kbId, id.getExternalId());
      if (byExternalId != null) {
        return byExternalId;
      }
    }

    if (StringUtils.hasText(id.getSourceUrl())) {
      Document bySourceUrl = documentMapper.selectBySourceUrlForUpdate(kbId, id.getSourceUrl());
      if (bySourceUrl != null) {
        return bySourceUrl;
      }
    }

    if (StringUtils.hasText(id.getContentMd5())) {
      return documentMapper.selectByContentMd5ForUpdate(kbId, id.getContentMd5());
    }

    return null;
  }

  private IngestResult doCreate(IngestCommand cmd) {
    Document doc = new Document();
    doc.setKbId(cmd.getKbId());
    doc.setFilename(cmd.getFilename());
    doc.setStorageKey(cmd.getStorageKey());
    doc.setFileSize(cmd.getSizeBytes());
    doc.setFileType(cmd.getContentType());
    doc.setFileMd5(cmd.getFileBytesMd5());
    doc.setExternalId(cmd.getIdentity().getExternalId());
    doc.setSourceUrl(cmd.getIdentity().getSourceUrl());
    doc.setContentMd5(cmd.getIdentity().getContentMd5());
    doc.setStorageBucket(cmd.getStorageBucket());
    doc.setIngestSource(cmd.getIngestSource());
    doc.setIndexedContent(cmd.getIndexedContent());
    doc.setParseStatus(STATUS_PENDING);
    doc.setChunkCount(0);
    doc.setChunkType(cmd.getChunkType());
    // 压缩包字段：容器（archiveFormat 非空）file_type 存格式 token；子文档回指容器
    doc.setParentDocumentId(cmd.getParentDocumentId());
    doc.setArchiveEntryPath(cmd.getArchiveEntryPath());
    final boolean container = StringUtils.hasText(cmd.getArchiveFormat());
    if (container) {
      doc.setFileType(cmd.getArchiveFormat());
    }
    doc.setCreatedAt(LocalDateTime.now());
    documentMapper.insert(doc);

    final Long docId = doc.getId();
    // 容器派发到 archive-expand（解压）；普通文档/子文档派发到 document-process（解析）
    afterCommit(
        () -> {
          if (container) {
            dispatchArchiveExpandAsync(docId);
          } else {
            dispatchMqAsync(docId, "create");
          }
        });
    metrics.recordIngestCreated();
    return IngestResult.created(docId);
  }

  private void dispatchArchiveExpandAsync(Long containerId) {
    MQ_DISPATCH_EXECUTOR.execute(
        () -> {
          try {
            archiveExpandProducer.send(containerId);
          } catch (Exception e) {
            log.error("Archive expand dispatch failed: containerId={} err={}", containerId, e.getMessage(), e);
          }
        });
  }

  private void dispatchMqAsync(Long documentId, String action) {
    // afterCommit 回调跑在 HTTP 请求线程上，MQ 派发必须移交后台线程，
    // 否则一旦 RocketMQ broker 抖动，前端 register 调用就会被吃掉 sendMsgTimeout × retries。
    MQ_DISPATCH_EXECUTOR.execute(
        () -> {
          long start = System.currentTimeMillis();
          try {
            mqProducer.send(documentId);
            log.info(
                "MQ dispatched: docId={} action={} latencyMs={}",
                documentId,
                action,
                System.currentTimeMillis() - start);
          } catch (Exception e) {
            log.error(
                "MQ dispatch failed: docId={} action={} latencyMs={} err={}",
                documentId,
                action,
                System.currentTimeMillis() - start,
                e.getMessage(),
                e);
          }
        });
  }

  private IngestResult doReplace(Document old, IngestCommand cmd) {
    final String oldBucket = old.getStorageBucket();
    final String oldKey = old.getStorageKey();
    final Long oldDocId = old.getId();

    documentMapper.updateStatus(oldDocId, STATUS_REPROCESSING);
    chunkMapper.deleteByDocumentId(oldDocId);
    documentMapper.replaceFields(oldDocId, cmd);
    documentMapper.updateStatus(oldDocId, STATUS_PENDING);

    afterCommit(
        () -> {
          esIndexService.deleteByDocId(oldDocId);
          if (StringUtils.hasText(oldBucket) && StringUtils.hasText(oldKey)) {
            objectStorage.delete(oldBucket, oldKey);
          }
          dispatchMqAsync(oldDocId, "replace");
        });

    metrics.recordIngestReplaced();
    return IngestResult.replaced(oldDocId);
  }

  private void afterCommit(Runnable action) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            action.run();
          }
        });
  }

  private void validate(IngestCommand cmd) {
    if (cmd == null) {
      throw new BizException(400, "INGEST_COMMAND_REQUIRED");
    }
    if (cmd.getKbId() == null) {
      throw new BizException(400, "KB_ID_REQUIRED");
    }
    if (cmd.getIdentity() == null
        || (!StringUtils.hasText(cmd.getIdentity().getExternalId())
            && !StringUtils.hasText(cmd.getIdentity().getSourceUrl())
            && !StringUtils.hasText(cmd.getIdentity().getContentMd5()))) {
      throw new BizException(400, "DOC_IDENTITY_REQUIRED");
    }
    if (!StringUtils.hasText(cmd.getFilename())) {
      throw new BizException(400, "FILENAME_REQUIRED");
    }
    if (!StringUtils.hasText(cmd.getStorageKey())) {
      throw new BizException(400, "STORAGE_KEY_REQUIRED");
    }
  }
}
