package com.ragforge.service.ingest;

import com.ragforge.common.BizException;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.model.dto.Identity;
import com.ragforge.model.dto.IngestCommand;
import com.ragforge.model.dto.IngestResult;
import com.ragforge.model.dto.OnConflict;
import com.ragforge.model.entity.Document;
import com.ragforge.mq.DocumentProcessProducer;
import com.ragforge.pipeline.indexer.EsIndexService;
import com.ragforge.storage.ObjectStorage;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class IngestServiceImpl implements IngestService {

  private static final String STATUS_PENDING = "PENDING";
  private static final String STATUS_REPROCESSING = "REPROCESSING";

  private final DocumentMapper documentMapper;
  private final DocumentChunkMapper chunkMapper;
  private final DocumentProcessProducer mqProducer;
  private final EsIndexService esIndexService;
  private final ObjectStorage objectStorage;

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
        yield IngestResult.skipped(existing.getId());
      }
      case REPLACE -> md5Same ? IngestResult.skipped(existing.getId()) : doReplace(existing, cmd);
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
    doc.setCreatedAt(LocalDateTime.now());
    documentMapper.insert(doc);

    afterCommit(() -> mqProducer.send(doc.getId()));
    return IngestResult.created(doc.getId());
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
          mqProducer.send(oldDocId);
        });

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
