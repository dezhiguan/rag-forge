package com.ragforge.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class IngestServiceImplTest {

  @Mock private DocumentMapper documentMapper;
  @Mock private DocumentChunkMapper chunkMapper;
  @Mock private DocumentProcessProducer mqProducer;
  @Mock private EsIndexService esIndexService;
  @Mock private ObjectStorage objectStorage;

  private IngestServiceImpl ingestService;

  @BeforeEach
  void setUp() {
    ingestService =
        new IngestServiceImpl(documentMapper, chunkMapper, mqProducer, esIndexService, objectStorage);
    TransactionSynchronizationManager.initSynchronization();
  }

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void resolveIdentityPrefersExternalIdOverSourceUrlAndMd5() {
    Document external = existing(10L, "old-key", "md5");
    when(documentMapper.selectByExternalIdForUpdate(1L, "ext")).thenReturn(external);

    IngestResult result =
        ingestService.register(
            command(1L, identity("ext", "https://example.com/a", "md5"), OnConflict.SKIP));

    assertThat(result.getDocumentId()).isEqualTo(10L);
    verify(documentMapper, never()).selectBySourceUrlForUpdate(eq(1L), eq("https://example.com/a"));
    verify(documentMapper, never()).selectByContentMd5ForUpdate(eq(1L), eq("md5"));
  }

  @Test
  void resolveIdentityFallsBackFromSourceUrlToMd5() {
    Document bySourceUrl = existing(20L, "old-key", "md5");
    when(documentMapper.selectBySourceUrlForUpdate(1L, "https://example.com/a"))
        .thenReturn(bySourceUrl);

    IngestResult sourceResult =
        ingestService.register(
            command(1L, identity(null, "https://example.com/a", "md5"), OnConflict.SKIP));
    assertThat(sourceResult.getDocumentId()).isEqualTo(20L);
    verify(documentMapper, never()).selectByContentMd5ForUpdate(eq(1L), eq("md5"));

    Document byMd5 = existing(30L, "old-key", "md5");
    when(documentMapper.selectByContentMd5ForUpdate(1L, "md5")).thenReturn(byMd5);
    IngestResult md5Result =
        ingestService.register(command(1L, identity(null, null, "md5"), OnConflict.SKIP));
    assertThat(md5Result.getDocumentId()).isEqualTo(30L);
  }

  @Test
  void rejectConflictThrowsWithoutSideEffects() {
    when(documentMapper.selectByExternalIdForUpdate(1L, "ext"))
        .thenReturn(existing(10L, "old-key", "old-md5"));

    assertThatThrownBy(
            () -> ingestService.register(command(1L, identity("ext", null, "new-md5"), OnConflict.REJECT)))
        .isInstanceOf(BizException.class)
        .hasMessage("DOC_IDENTITY_CONFLICT")
        .satisfies(
            ex -> {
              BizException biz = (BizException) ex;
              assertThat(biz.getCode()).isEqualTo(409);
              assertThat(biz.getData()).containsEntry("existingDocId", 10L);
            });

    verifyNoInteractions(chunkMapper, mqProducer, esIndexService, objectStorage);
  }

  @Test
  void skipReturnsSkippedWhenMd5IsSame() {
    when(documentMapper.selectByExternalIdForUpdate(1L, "ext"))
        .thenReturn(existing(10L, "old-key", "same-md5"));

    IngestResult result =
        ingestService.register(command(1L, identity("ext", null, "same-md5"), OnConflict.SKIP));

    assertThat(result.getStatus()).isEqualTo(IngestResult.Status.SKIPPED);
    assertThat(result.getDocumentId()).isEqualTo(10L);
    verifyNoInteractions(chunkMapper, mqProducer, esIndexService, objectStorage);
  }

  @Test
  void skipThrowsWhenMd5Changed() {
    when(documentMapper.selectByExternalIdForUpdate(1L, "ext"))
        .thenReturn(existing(10L, "old-key", "old-md5"));

    assertThatThrownBy(
            () -> ingestService.register(command(1L, identity("ext", null, "new-md5"), OnConflict.SKIP)))
        .isInstanceOf(BizException.class)
        .hasMessage("DOC_CONTENT_CHANGED_USE_REPLACE")
        .satisfies(
            ex -> {
              BizException biz = (BizException) ex;
              assertThat(biz.getCode()).isEqualTo(409);
              assertThat(biz.getData()).containsEntry("existingDocId", 10L);
            });

    verifyNoInteractions(chunkMapper, mqProducer, esIndexService, objectStorage);
  }

  @Test
  void createRegistersMqAfterCommitOnly() {
    when(documentMapper.insert(org.mockito.ArgumentMatchers.any(Document.class)))
        .thenAnswer(
            invocation -> {
              Document doc = invocation.getArgument(0);
              doc.setId(99L);
              return 1;
            });

    IngestResult result =
        ingestService.register(command(1L, identity("ext", null, "md5"), OnConflict.REJECT));

    assertThat(result.getStatus()).isEqualTo(IngestResult.Status.CREATED);
    verifyNoInteractions(mqProducer);
    runAfterCommit();
    verify(mqProducer).send(eq(99L));
  }

  @Test
  void replaceUpdatesExistingDocAndRunsExternalSideEffectsAfterCommit() {
    Document old = existing(10L, "old-key", "old-md5");
    when(documentMapper.selectByExternalIdForUpdate(1L, "ext")).thenReturn(old);

    IngestResult result =
        ingestService.register(command(1L, identity("ext", null, "new-md5"), OnConflict.REPLACE));

    assertThat(result.getStatus()).isEqualTo(IngestResult.Status.REPLACED);
    verify(documentMapper).updateStatus(eq(10L), eq("REPROCESSING"));
    verify(chunkMapper).deleteByDocumentId(eq(10L));
    verify(documentMapper).replaceFields(eq(10L), org.mockito.ArgumentMatchers.any(IngestCommand.class));
    verify(documentMapper).updateStatus(eq(10L), eq("PENDING"));
    verifyNoInteractions(mqProducer, esIndexService, objectStorage);

    runAfterCommit();
    verify(esIndexService).deleteByDocId(eq(10L));
    verify(objectStorage).delete(eq("old-bucket"), eq("old-key"));
    verify(mqProducer).send(eq(10L));
  }

  @Test
  void replaceSkipsWhenMd5IsSame() {
    when(documentMapper.selectByExternalIdForUpdate(1L, "ext"))
        .thenReturn(existing(10L, "old-key", "same-md5"));

    IngestResult result =
        ingestService.register(command(1L, identity("ext", null, "same-md5"), OnConflict.REPLACE));

    assertThat(result.getStatus()).isEqualTo(IngestResult.Status.SKIPPED);
    verifyNoInteractions(chunkMapper, mqProducer, esIndexService, objectStorage);
  }

  private void runAfterCommit() {
    for (TransactionSynchronization synchronization :
        TransactionSynchronizationManager.getSynchronizations()) {
      synchronization.afterCommit();
    }
  }

  private Document existing(Long id, String storageKey, String contentMd5) {
    Document doc = new Document();
    doc.setId(id);
    doc.setKbId(1L);
    doc.setStorageBucket("old-bucket");
    doc.setStorageKey(storageKey);
    doc.setContentMd5(contentMd5);
    return doc;
  }

  private IngestCommand command(Long kbId, Identity identity, OnConflict onConflict) {
    IngestCommand cmd = new IngestCommand();
    cmd.setKbId(kbId);
    cmd.setStorageBucket("new-bucket");
    cmd.setStorageKey("new-key");
    cmd.setFilename("new.pdf");
    cmd.setSizeBytes(20L);
    cmd.setContentType("application/pdf");
    cmd.setFileBytesMd5("file-bytes-md5");
    cmd.setIdentity(identity);
    cmd.setOnConflict(onConflict);
    cmd.setIngestSource("unit");
    cmd.setChunkType("recursive");
    return cmd;
  }

  private Identity identity(String externalId, String sourceUrl, String contentMd5) {
    Identity identity = new Identity();
    identity.setExternalId(externalId);
    identity.setSourceUrl(sourceUrl);
    identity.setContentMd5(contentMd5);
    return identity;
  }
}
