package com.ragforge.document.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.dto.IngestResult;
import com.ragforge.model.dto.PresignUploadRequest;
import com.ragforge.model.dto.RechunkRequest;
import com.ragforge.model.dto.RegisterUploadRequest;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.archive.ArchiveFormatDetector;
import com.ragforge.mq.ArchiveExpandProducer;
import com.ragforge.mq.DocumentProcessProducer;
import com.ragforge.pipeline.chunker.ChunkParams;
import com.ragforge.pipeline.chunker.ChunkerProfile;
import com.ragforge.pipeline.chunker.ChunkingService;
import com.ragforge.security.KbAccessGuard;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import com.ragforge.service.ingest.IngestService;
import com.ragforge.service.upload.UploadTokenService;
import com.ragforge.service.upload.UploadTokenService.TokenPayload;
import com.ragforge.storage.ObjectMeta;
import com.ragforge.storage.ObjectStorage;
import com.ragforge.storage.StorageProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentUploadApplicationServiceImplTest {

  @Mock private IngestService ingestService;
  @Mock private ObjectStorage objectStorage;
  @Mock private KbAccessGuard kbAccessGuard;
  @Mock private UploadTokenService uploadTokenService;
  @Mock private DocumentMapper documentMapper;
  @Mock private DocumentChunkMapper documentChunkMapper;
  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private ChunkingService chunkingService;
  @Mock private DocumentProcessProducer mqProducer;
  @Mock private ArchiveExpandProducer archiveExpandProducer;

  private final ArchiveFormatDetector archiveFormatDetector = new ArchiveFormatDetector();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private StorageProperties storageProperties;
  private DocumentUploadApplicationServiceImpl service;

  @BeforeEach
  void setUp() {
    RagAuthContextHolder.set(
        new RagAuthContext(7L, "USER", Set.of(16L), Set.of(16L), Set.of(), "USER", "7"));
    storageProperties = new StorageProperties();
    service =
        new DocumentUploadApplicationServiceImpl(
            ingestService,
            objectStorage,
            storageProperties,
            kbAccessGuard,
            objectMapper,
            uploadTokenService,
            documentMapper,
            documentChunkMapper,
            knowledgeBaseMapper,
            chunkingService,
            mqProducer,
            archiveExpandProducer,
            archiveFormatDetector);
  }

  @AfterEach
  void clearContext() {
    RagAuthContextHolder.clear();
  }

  @Test
  void presignUploadCleansFilenameDefaultsBucketAndIssuesToken() {
    when(kbAccessGuard.canWrite(16L)).thenReturn(true);
    when(uploadTokenService.tokenTtl()).thenReturn(Duration.ofMinutes(30));
    when(uploadTokenService.issue(any(TokenPayload.class)))
        .thenAnswer(
            invocation -> {
              TokenPayload payload = invocation.getArgument(0);
              payload.setExpiresAt(1_800L);
              return "token-1";
            });
    when(objectStorage.presignedPut(eq("local"), any(), any(), any())).thenReturn("https://put");

    PresignUploadRequest request = new PresignUploadRequest();
    request.setKbId(16L);
    request.setFilename("../bad\rname.pdf");
    request.setDeclaredSize(123L);

    Map<String, Object> result = service.presignUpload(request);

    assertThat(result.get("uploadToken")).isEqualTo("token-1");
    assertThat(result.get("storageBucket")).isEqualTo("local");
    assertThat(result.get("storageKey").toString()).startsWith("kb_16/");
    assertThat(result.get("storageKey").toString()).endsWith("bad_name.pdf");
    assertThat(result.get("presignedPutUrl")).isEqualTo("https://put");
  }

  @Test
  void presignUploadValidatesWritePermissionSizeAndDefaultFilename() {
    PresignUploadRequest request = new PresignUploadRequest();
    request.setKbId(16L);
    request.setFilename("   ");
    request.setDeclaredSize(0L);

    assertThatThrownBy(() -> service.presignUpload(request))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(403);

    when(kbAccessGuard.canWrite(16L)).thenReturn(true);
    assertThatThrownBy(() -> service.presignUpload(request))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(400);

    request.setDeclaredSize(1L);
    when(uploadTokenService.tokenTtl()).thenReturn(Duration.ofMinutes(30));
    when(uploadTokenService.issue(any(TokenPayload.class)))
        .thenAnswer(
            invocation -> {
              TokenPayload payload = invocation.getArgument(0);
              payload.setExpiresAt(1_800L);
              return "token";
            });
    when(objectStorage.presignedPut(eq("local"), any(), any(), any())).thenReturn("https://put");

    Map<String, Object> result = service.presignUpload(request);

    assertThat(result.get("storageKey").toString()).endsWith("/upload.bin");
  }

  @Test
  void registerUploadedDocumentValidatesObjectAndDeletesSkippedUploads() {
    TokenPayload payload = new TokenPayload();
    payload.setKbId(16L);
    payload.setStorageBucket("bucket");
    payload.setStorageKey("key");
    payload.setFilename("doc.pdf");
    payload.setDeclaredSize(5L);
    payload.setContentType("application/pdf");
    when(uploadTokenService.consume("token")).thenReturn(payload);
    ObjectMeta meta = new ObjectMeta();
    meta.setSizeBytes(5L);
    meta.setContentType("application/pdf");
    when(objectStorage.head("bucket", "key")).thenReturn(meta);
    when(ingestService.register(any())).thenReturn(IngestResult.skipped(99L));
    RegisterUploadRequest request = new RegisterUploadRequest();
    request.setUploadToken("token");
    request.setKbId(16L);

    Map<String, Object> result = service.registerUploadedDocument(request);

    assertThat(result).containsEntry("documentId", 99L).containsEntry("status", "SKIPPED");
    verify(objectStorage).delete("bucket", "key");
  }

  @Test
  void registerUploadedDocumentRejectsWrongKbAndMissingObject() {
    TokenPayload payload = new TokenPayload();
    payload.setKbId(16L);
    payload.setStorageBucket("bucket");
    payload.setStorageKey("key");
    payload.setDeclaredSize(5L);
    when(uploadTokenService.consume("token")).thenReturn(payload);
    RegisterUploadRequest request = new RegisterUploadRequest();
    request.setUploadToken("token");
    request.setKbId(17L);

    assertThatThrownBy(() -> service.registerUploadedDocument(request))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(403);

    request.setKbId(16L);
    when(objectStorage.head("bucket", "key")).thenReturn(null);
    assertThatThrownBy(() -> service.registerUploadedDocument(request))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(422);
  }

  @Test
  void registerUploadedDocumentRequiresAuthContext() {
    RagAuthContextHolder.clear();
    when(uploadTokenService.consume("token")).thenReturn(new TokenPayload());
    RegisterUploadRequest request = new RegisterUploadRequest();
    request.setUploadToken("token");

    assertThatThrownBy(() -> service.registerUploadedDocument(request))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(403);
  }

  @Test
  void reprocessAllowsFailedOrCompletedAndRejectsInProgress() {
    Document failed = doc(30L, 16L, "failed");
    when(documentMapper.selectById(30L)).thenReturn(failed);

    Map<String, Object> result = service.reprocess(30L);

    assertThat(result).containsEntry("status", "PENDING");
    verify(documentMapper).updateStatus(30L, "PENDING");
    verify(mqProducer).send(30L);

    when(documentMapper.selectById(31L)).thenReturn(doc(31L, 16L, "processing"));
    assertThatThrownBy(() -> service.reprocess(31L))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(409);
  }

  @Test
  void reprocessRejectsMissingAndUnsupportedStatus() {
    when(documentMapper.selectById(404L)).thenReturn(null);
    assertThatThrownBy(() -> service.reprocess(404L))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(404);

    when(documentMapper.selectById(32L)).thenReturn(doc(32L, 16L, "cancelled"));
    assertThatThrownBy(() -> service.reprocess(32L))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(409);
  }

  @Test
  void rechunkImageOnlyDocumentUsesImagePipeline() {
    Document image = doc(40L, 16L, "completed");
    image.setFileType("image/png");
    when(documentMapper.selectById(40L)).thenReturn(image);
    when(kbAccessGuard.canWrite(16L)).thenReturn(true);
    when(documentChunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

    Map<String, Object> result = service.rechunk(40L, null);

    assertThat(result).containsEntry("newStrategy", "IMAGE_PIPELINE");
    verify(documentMapper).updateRechunkRequest(40L, null, null, "REPROCESSING");
    verify(mqProducer).send(40L);
  }

  @Test
  void rechunkFixedWindowStoresNormalizedStrategyAndParams() {
    Document doc = doc(41L, 16L, "completed");
    doc.setIndexedContent("x".repeat(3000));
    DocumentChunk chunk = new DocumentChunk();
    chunk.setDocId(41L);
    chunk.setChunkerStrategy("MARKDOWN_HEADING");
    when(documentMapper.selectById(41L)).thenReturn(doc);
    when(kbAccessGuard.canWrite(16L)).thenReturn(true);
    when(documentChunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(chunk));
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(16L);
    when(knowledgeBaseMapper.selectById(16L)).thenReturn(kb);
    ChunkerProfile profile = new ChunkerProfile();
    profile.setParams(new ChunkParams());
    when(chunkingService.resolveProfile(kb)).thenReturn(profile);
    RechunkRequest request = new RechunkRequest();
    request.setStrategy(" fixed_window ");
    request.setChunkSize(512);
    request.setChunkOverlap(64);

    Map<String, Object> result = service.rechunk(41L, request);

    assertThat(result).containsEntry("oldStrategy", "MARKDOWN_HEADING");
    assertThat(result).containsEntry("newStrategy", "FIXED_WINDOW");
    ArgumentCaptor<String> paramsCaptor = ArgumentCaptor.forClass(String.class);
    verify(documentMapper).updateRechunkRequest(eq(41L), eq("FIXED_WINDOW"), paramsCaptor.capture(), eq("REPROCESSING"));
    assertThat(paramsCaptor.getValue()).contains("\"chunkSize\":512", "\"overlap\":64");
  }

  @Test
  void rechunkWithoutStrategyUsesKbDefaultAndRejectsNoWrite() {
    Document doc = doc(42L, 16L, "completed");
    when(documentMapper.selectById(42L)).thenReturn(doc);
    when(kbAccessGuard.canWrite(16L)).thenReturn(false);

    assertThatThrownBy(() -> service.rechunk(42L, null))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(403);

    when(kbAccessGuard.canWrite(16L)).thenReturn(true);
    when(documentChunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(new DocumentChunk()));
    KnowledgeBase kb = new KnowledgeBase();
    when(knowledgeBaseMapper.selectById(16L)).thenReturn(kb);
    ChunkerProfile profile = new ChunkerProfile();
    profile.setDefaultStrategy("SEMANTIC");
    when(chunkingService.resolveProfile(kb)).thenReturn(profile);

    Map<String, Object> result = service.rechunk(42L, null);

    assertThat(result).containsEntry("newStrategy", "SEMANTIC");
    verify(documentMapper).updateRechunkRequest(42L, null, null, "REPROCESSING");
  }

  @Test
  void rechunkRejectsMissingInProgressAndInvalidProfileJson() throws Exception {
    when(documentMapper.selectById(404L)).thenReturn(null);
    assertThatThrownBy(() -> service.rechunk(404L, null))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(404);

    when(documentMapper.selectById(43L)).thenReturn(doc(43L, 16L, "PENDING"));
    when(kbAccessGuard.canWrite(16L)).thenReturn(true);
    assertThatThrownBy(() -> service.rechunk(43L, null))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(409);
  }

  private static Document doc(Long id, Long kbId, String status) {
    Document doc = new Document();
    doc.setId(id);
    doc.setKbId(kbId);
    doc.setParseStatus(status);
    doc.setFilename("doc.pdf");
    return doc;
  }
}
