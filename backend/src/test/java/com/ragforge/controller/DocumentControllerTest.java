package com.ragforge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.ragforge.common.BizException;
import com.ragforge.common.GlobalExceptionHandler;
import com.ragforge.common.PageResult;
import com.ragforge.common.RelayUploadLimits;
import com.ragforge.document.service.impl.DocumentUploadApplicationServiceImpl;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.pipeline.chunker.ChunkingService;
import com.ragforge.model.dto.IngestCommand;
import com.ragforge.model.dto.IngestResult;
import com.ragforge.model.entity.Document;
import com.ragforge.model.vo.DocumentChunkVO;
import com.ragforge.model.vo.DocumentDetailVO;
import com.ragforge.model.vo.DocumentStatusVO;
import com.ragforge.model.vo.DocumentUploadResultVO;
import com.ragforge.model.vo.DocumentVO;
import com.ragforge.mq.DocumentProcessProducer;
import com.ragforge.security.KbAccessGuard;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import com.ragforge.service.ingest.IngestService;
import com.ragforge.service.DocumentService;
import com.ragforge.service.upload.UploadTokenService;
import com.ragforge.service.upload.UploadTokenService.TokenPayload;
import com.ragforge.storage.ObjectMeta;
import com.ragforge.storage.ObjectStorage;
import com.ragforge.storage.StorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
class DocumentControllerTest {

  @Mock private DocumentService documentService;
  @Mock private IngestService ingestService;
  @Mock private ObjectStorage objectStorage;
  @Mock private KbAccessGuard kbAccessGuard;
  @Mock private UploadTokenService uploadTokenService;
  @Mock private DocumentMapper documentMapper;
  @Mock private DocumentChunkMapper documentChunkMapper;
  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private ChunkingService chunkingService;
  @Mock private DocumentProcessProducer mqProducer;

  private MockMvc mockMvc;
  private StorageProperties storageProperties;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    storageProperties = new StorageProperties();
    storageProperties.getAliyun().setBucket("test-bucket");

    mockMvc =
        standaloneSetup(
                new DocumentController(
                    documentService,
                    new DocumentUploadApplicationServiceImpl(
                        ingestService,
                        objectStorage,
                        storageProperties,
                        kbAccessGuard,
                        new ObjectMapper(),
                        uploadTokenService,
                        documentMapper,
                        documentChunkMapper,
                        knowledgeBaseMapper,
                        chunkingService,
                        mqProducer)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(
                new ResourceHttpMessageConverter(), new MappingJackson2HttpMessageConverter())
            .setValidator(validator)
            .build();
  }

  @AfterEach
  void tearDown() {
    RagAuthContextHolder.clear();
  }

  @Test
  void uploadV5_streamsToObjectStorageAndRegistersIngest() throws Exception {
    when(kbAccessGuard.canWrite(16L)).thenReturn(true);
    when(ingestService.register(any()))
        .thenReturn(IngestResult.created(9912L));

    MockMultipartFile file =
        new MockMultipartFile("file", "jd.pdf", "application/pdf", "hello".getBytes());
    mockMvc
        .perform(
            multipart("/api/v1/documents")
                .file(file)
                .param(
                    "meta",
                    """
                    {
                      "kbId": 16,
                      "identity": { "externalId": "boss-1" },
                      "onConflict": "REJECT",
                      "ingestSource": "boss-scraper",
                      "chunkType": "JD",
                      "metadata": {"source":"boss"}
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentId").value(9912))
        .andExpect(jsonPath("$.status").value("CREATED"));

    verify(objectStorage).put(eq("test-bucket"), org.mockito.ArgumentMatchers.contains("/16/"), any(), any());
    verify(ingestService).register(any());
  }

  @Test
  void uploadV5_preservesClientBusinessContentMd5AndSetsFileBytesMd5() throws Exception {
    when(kbAccessGuard.canWrite(16L)).thenReturn(true);
    when(ingestService.register(any())).thenReturn(IngestResult.created(9912L));
    ArgumentCaptor<IngestCommand> captor = ArgumentCaptor.forClass(IngestCommand.class);

    MockMultipartFile file =
        new MockMultipartFile("file", "jd.pdf", "application/pdf", "hello".getBytes());
    mockMvc
        .perform(
            multipart("/api/v1/documents")
                .file(file)
                .param(
                    "meta",
                    """
                    {
                      "kbId": 16,
                      "identity": { "externalId": "boss-1", "contentMd5": "business-md5" },
                      "onConflict": "REJECT"
                    }
                    """))
        .andExpect(status().isOk());

    verify(ingestService).register(captor.capture());
    IngestCommand cmd = captor.getValue();
    assertThat(cmd.getIdentity().getContentMd5()).isEqualTo("business-md5");
    assertThat(cmd.getFileBytesMd5())
        .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
  }

  @Test
  void uploadV5_fallsBackToFileBytesMd5WhenBusinessContentMd5Missing() throws Exception {
    when(kbAccessGuard.canWrite(16L)).thenReturn(true);
    when(ingestService.register(any())).thenReturn(IngestResult.created(9912L));
    ArgumentCaptor<IngestCommand> captor = ArgumentCaptor.forClass(IngestCommand.class);

    MockMultipartFile file =
        new MockMultipartFile("file", "jd.pdf", "application/pdf", "hello".getBytes());
    mockMvc
        .perform(
            multipart("/api/v1/documents")
                .file(file)
                .param(
                    "meta",
                    """
                    {
                      "kbId": 16,
                      "identity": { "externalId": "boss-1" },
                      "onConflict": "REJECT"
                    }
                    """))
        .andExpect(status().isOk());

    verify(ingestService).register(captor.capture());
    IngestCommand cmd = captor.getValue();
    assertThat(cmd.getIdentity().getContentMd5())
        .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    assertThat(cmd.getFileBytesMd5())
        .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
  }

  @Test
  void uploadV5_deletesNewObjectWhenRegisterSkipped() throws Exception {
    when(kbAccessGuard.canWrite(16L)).thenReturn(true);
    when(ingestService.register(any())).thenReturn(IngestResult.skipped(8810L));

    MockMultipartFile file =
        new MockMultipartFile("file", "jd.pdf", "application/pdf", "hello".getBytes());
    mockMvc
        .perform(
            multipart("/api/v1/documents")
                .file(file)
                .param(
                    "meta",
                    """
                    {
                      "kbId": 16,
                      "identity": { "externalId": "boss-1" },
                      "onConflict": "SKIP"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentId").value(8810))
        .andExpect(jsonPath("$.status").value("SKIPPED"));

    verify(objectStorage).delete(eq("test-bucket"), org.mockito.ArgumentMatchers.contains("/16/"));
  }

  @Test
  void uploadV5_conflictResponseIncludesExistingDocId() throws Exception {
    when(kbAccessGuard.canWrite(16L)).thenReturn(true);
    when(ingestService.register(any()))
        .thenThrow(new BizException(409, "DOC_IDENTITY_CONFLICT", java.util.Map.of("existingDocId", 8810L)));

    MockMultipartFile file =
        new MockMultipartFile("file", "jd.pdf", "application/pdf", "hello".getBytes());
    mockMvc
        .perform(
            multipart("/api/v1/documents")
                .file(file)
                .param(
                    "meta",
                    """
                    {
                      "kbId": 16,
                      "identity": { "externalId": "boss-1" },
                      "onConflict": "REJECT"
                    }
                    """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("DOC_IDENTITY_CONFLICT"))
        .andExpect(jsonPath("$.existingDocId").value(8810));

    verify(objectStorage).delete(eq("test-bucket"), org.mockito.ArgumentMatchers.contains("/16/"));
  }

  @Test
  void uploadV5_rejectsFilesAboveRelayLimit() throws Exception {
    when(kbAccessGuard.canWrite(16L)).thenReturn(true);
    MockMultipartFile file =
        new MockMultipartFile("file", "large.pdf", "application/pdf", new byte[0]) {
          @Override
          public long getSize() {
            return 60L * 1024L * 1024L;
          }
        };
    mockMvc
        .perform(
            multipart("/api/v1/documents")
                .file(file)
                .param("meta", "{\"kbId\":16,\"identity\":{\"externalId\":\"boss-large\"}}"))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.error").value("FILE_TOO_LARGE_FOR_RELAY"))
        .andExpect(jsonPath("$.presignUrl").value("/api/v1/uploads/presign"))
        .andExpect(jsonPath("$.limitMb").value(50));

    verifyNoInteractions(objectStorage, ingestService);
  }

  @Test
  void globalExceptionHandler_translatesMaxUploadSizeToPayloadTooLarge() {
    org.springframework.web.multipart.MaxUploadSizeExceededException ex =
        new org.springframework.web.multipart.MaxUploadSizeExceededException(
            RelayUploadLimits.RELAY_UPLOAD_LIMIT_BYTES);

    org.springframework.http.ResponseEntity<java.util.Map<String, Object>> response =
        new com.ragforge.common.GlobalExceptionHandler().handleMaxUploadSize(ex);

    assertThat(response.getStatusCode().value()).isEqualTo(413);
    java.util.Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body).containsEntry("error", "FILE_TOO_LARGE_FOR_RELAY");
    assertThat(body).containsEntry("presignUrl", "/api/v1/uploads/presign");
    assertThat(body).containsEntry("limitMb", 50);
  }

  @Test
  void presignUpload_returnsTokenAndPresignedPutUrl() throws Exception {
    RagAuthContextHolder.set(authContext("tn_test", Set.of(16L)));
    when(kbAccessGuard.canWrite(16L)).thenReturn(true);
    when(uploadTokenService.issue(any()))
        .thenAnswer(
            inv -> {
              TokenPayload payload = inv.getArgument(0);
              payload.setUploadToken("uplt_signed-token");
              payload.setExpiresAt(1_783_000_000L);
              return "uplt_signed-token";
            });
    when(objectStorage.presignedPut(eq("test-bucket"), any(), any(), any()))
        .thenReturn("https://oss.example/put");

    mockMvc
        .perform(
            post("/api/v1/uploads/presign")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"kbId":16,"filename":"big.pdf","contentType":"application/pdf","declaredSize":83886080}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.uploadToken").value("uplt_signed-token"))
        .andExpect(jsonPath("$.presignedPutUrl").value("https://oss.example/put"))
        .andExpect(jsonPath("$.storageBucket").value("test-bucket"))
        .andExpect(jsonPath("$.storageKey").value(org.hamcrest.Matchers.containsString("tn_test/kb_16/uplt_")))
        .andExpect(jsonPath("$.expiresAt").value("2026-07-02T13:46:40Z"));
  }

  @Test
  void registerUploadedDocument_consumesTokenHeadsObjectAndRegisters() throws Exception {
    RagAuthContextHolder.set(authContext("tn_test", Set.of(16L)));
    TokenPayload payload = tokenPayload("tn_test", 16L, 83886080L);
    when(uploadTokenService.consume("uplt_signed-token")).thenReturn(payload);
    when(objectStorage.head("test-bucket", "tn_test/kb_16/uplt_a/big.pdf"))
        .thenReturn(new ObjectMeta("application/pdf", 83886080L, "etag", null));
    when(ingestService.register(any())).thenReturn(IngestResult.replaced(9912L));

    mockMvc
        .perform(
            post("/api/v1/documents/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"uploadToken":"uplt_signed-token","kbId":16,"identity":{"externalId":"boss-1"},"onConflict":"REPLACE","ingestSource":"web-upload-large"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentId").value(9912))
        .andExpect(jsonPath("$.status").value("REPLACED"));
  }

  @Test
  void registerUploadedDocument_replayReturnsTokenInvalid() throws Exception {
    when(uploadTokenService.consume("uplt_replay")).thenThrow(new BizException(409, "TOKEN_INVALID"));

    mockMvc
        .perform(
            post("/api/v1/documents/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"uploadToken\":\"uplt_replay\",\"kbId\":16}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.msg").value("TOKEN_INVALID"));
  }

  @Test
  void registerUploadedDocument_rejectsKbMismatchAfterGetDel() throws Exception {
    RagAuthContextHolder.set(authContext("tn_test", Set.of(16L)));
    when(uploadTokenService.consume("uplt_signed-token"))
        .thenReturn(tokenPayload("tn_test", 15L, 10L));

    mockMvc
        .perform(
            post("/api/v1/documents/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"uploadToken\":\"uplt_signed-token\",\"kbId\":16}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.msg").value("UPLOAD_TOKEN_KB_FORBIDDEN"));
  }

  @Test
  void registerUploadedDocument_rejectsSizeMismatch() throws Exception {
    RagAuthContextHolder.set(authContext("tn_test", Set.of(16L)));
    TokenPayload payload = tokenPayload("tn_test", 16L, 1024L);
    when(uploadTokenService.consume("uplt_signed-token")).thenReturn(payload);
    when(objectStorage.head("test-bucket", "tn_test/kb_16/uplt_a/big.pdf"))
        .thenReturn(new ObjectMeta("application/pdf", 10L * 1024L * 1024L, "etag", null));

    mockMvc
        .perform(
            post("/api/v1/documents/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"uploadToken\":\"uplt_signed-token\",\"kbId\":16,\"identity\":{\"externalId\":\"x\"}}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.msg").value("SIZE_MISMATCH"));
  }

  @Test
  void registerUploadedDocument_rejectsTenantMismatch() throws Exception {
    RagAuthContextHolder.set(authContext("tn_other", Set.of(16L)));
    when(uploadTokenService.consume("uplt_signed-token"))
        .thenReturn(tokenPayload("tn_test", 16L, 1024L));

    mockMvc
        .perform(
            post("/api/v1/documents/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"uploadToken\":\"uplt_signed-token\",\"kbId\":16}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.msg").value("UPLOAD_TOKEN_TENANT_FORBIDDEN"));
  }

  @Test
  void registerUploadedDocument_deletesObjectWhenRegisterSkipped() throws Exception {
    RagAuthContextHolder.set(authContext("tn_test", Set.of(16L)));
    TokenPayload payload = tokenPayload("tn_test", 16L, 83886080L);
    when(uploadTokenService.consume("uplt_signed-token")).thenReturn(payload);
    when(objectStorage.head("test-bucket", "tn_test/kb_16/uplt_a/big.pdf"))
        .thenReturn(new ObjectMeta("application/pdf", 83886080L, "etag", null));
    when(ingestService.register(any())).thenReturn(IngestResult.skipped(8810L));

    mockMvc
        .perform(
            post("/api/v1/documents/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"uploadToken":"uplt_signed-token","kbId":16,"identity":{"externalId":"boss-1"},"onConflict":"SKIP"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentId").value(8810))
        .andExpect(jsonPath("$.status").value("SKIPPED"));

    verify(objectStorage).delete("test-bucket", "tn_test/kb_16/uplt_a/big.pdf");
  }

  @Test
  void registerUploadedDocument_conflictExposesExistingDocIdViaGlobalExceptionHandler() throws Exception {
    RagAuthContextHolder.set(authContext("tn_test", Set.of(16L)));
    TokenPayload payload = tokenPayload("tn_test", 16L, 83886080L);
    when(uploadTokenService.consume("uplt_signed-token")).thenReturn(payload);
    when(objectStorage.head("test-bucket", "tn_test/kb_16/uplt_a/big.pdf"))
        .thenReturn(new ObjectMeta("application/pdf", 83886080L, "etag", null));
    when(ingestService.register(any()))
        .thenThrow(
            new BizException(
                409, "DOC_IDENTITY_CONFLICT", java.util.Map.of("existingDocId", 8810L)));

    mockMvc
        .perform(
            post("/api/v1/documents/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"uploadToken":"uplt_signed-token","kbId":16,"identity":{"externalId":"boss-1"},"onConflict":"REJECT"}
                    """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.msg").value("DOC_IDENTITY_CONFLICT"))
        .andExpect(jsonPath("$.data.existingDocId").value(8810));
  }

  @Test
  void upload_delegatesToService() throws Exception {
    DocumentUploadResultVO result = new DocumentUploadResultVO();
    result.setDocId(10L);
    when(documentService.upload(eq(1L), any(), eq(false))).thenReturn(result);

    MockMultipartFile file =
        new MockMultipartFile("file", "readme.txt", "text/plain", "hello".getBytes());

    mockMvc
        .perform(multipart("/api/v1/kb/1/documents").file(file))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.docId").value(10));
  }

  @Test
  void uploadAlias_acceptsKbIdQueryParam() throws Exception {
    DocumentUploadResultVO result = new DocumentUploadResultVO();
    result.setDocId(11L);
    when(documentService.upload(eq(2L), any(), eq(true))).thenReturn(result);

    MockMultipartFile file =
        new MockMultipartFile("file", "a.txt", "text/plain", "data".getBytes());

    mockMvc
        .perform(
            multipart("/api/v1/documents/upload")
                .file(file)
                .param("kbId", "2")
                .param("overwrite", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.docId").value(11));
  }

  @Test
  void listByKb_returnsPagedDocuments() throws Exception {
    DocumentVO doc = new DocumentVO();
    doc.setId(5L);
    when(documentService.listByKb(1L, 1, 20)).thenReturn(PageResult.of(1, 1, 20, List.of(doc)));

    mockMvc
        .perform(get("/api/v1/kb/1/documents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.list[0].id").value(5));
  }

  @Test
  void getById_returnsDocumentDetail() throws Exception {
    DocumentDetailVO detail = new DocumentDetailVO();
    detail.setId(7L);
    when(documentService.getById(7L)).thenReturn(detail);

    mockMvc
        .perform(get("/api/v1/documents/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(7));
  }

  @Test
  void listChunks_returnsPagedChunks() throws Exception {
    DocumentChunkVO chunk = new DocumentChunkVO();
    chunk.setChunkIndex(0);
    chunk.setContent("chunk text");
    when(documentService.listChunks(8L, 1, 20))
        .thenReturn(PageResult.of(1, 1, 20, List.of(chunk)));

    mockMvc
        .perform(get("/api/v1/documents/8/chunks"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.list[0].chunkIndex").value(0));
  }

  @Test
  void getStatus_returnsDocumentStatus() throws Exception {
    DocumentStatusVO status = new DocumentStatusVO();
    status.setParseStatus("completed");
    status.setChunkCount(3);
    when(documentService.getStatus(9L)).thenReturn(status);

    mockMvc
        .perform(get("/api/v1/documents/9/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.parseStatus").value("completed"));
  }

  @Test
  void download_delegatesToService() throws Exception {
    when(documentService.download(12L))
        .thenReturn(ResponseEntity.ok(new ByteArrayResource("file".getBytes())));

    mockMvc.perform(get("/api/v1/documents/12/download")).andExpect(status().isOk());
  }

  @Test
  void delete_delegatesToService() throws Exception {
    mockMvc.perform(delete("/api/v1/documents/13")).andExpect(status().isOk());

    verify(documentService).delete(13L);
  }

  @Test
  void reprocess_failedDocument_setsPendingAndSendsMq() throws Exception {
    Document doc = new Document();
    doc.setId(13L);
    doc.setParseStatus("FAILED");
    when(documentMapper.selectById(13L)).thenReturn(doc);

    mockMvc
        .perform(post("/api/v1/documents/13/reprocess"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentId").value(13))
        .andExpect(jsonPath("$.status").value("PENDING"));

    verify(documentMapper).updateStatus(13L, "PENDING");
    verify(mqProducer).send(13L);
  }

  @Test
  void reprocess_processingDocument_returnsConflict() throws Exception {
    Document doc = new Document();
    doc.setId(14L);
    doc.setParseStatus("PROCESSING");
    when(documentMapper.selectById(14L)).thenReturn(doc);

    mockMvc
        .perform(post("/api/v1/documents/14/reprocess"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.msg").value("ALREADY_IN_PROGRESS"));
  }

  @Test
  void uploadTextRoute_isRemoved() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/documents/text")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"kbId":1,"title":"note.txt","content":"hello world","chunkType":"GENERAL"}
                    """))
        .andExpect(status().isNotFound());
  }

  private static RagAuthContext authContext(String tenantId, Set<Long> writableKbIds) {
    return new RagAuthContext(
        100L, tenantId, "USER", Set.of(), writableKbIds, Set.of(), "user", "100");
  }

  private static TokenPayload tokenPayload(String tenantId, Long kbId, Long declaredSize) {
    TokenPayload payload = new TokenPayload();
    payload.setUploadToken("uplt_signed-token");
    payload.setTenantId(tenantId);
    payload.setKbId(kbId);
    payload.setStorageBucket("test-bucket");
    payload.setStorageKey("tn_test/kb_16/uplt_a/big.pdf");
    payload.setFilename("big.pdf");
    payload.setContentType("application/pdf");
    payload.setDeclaredSize(declaredSize);
    payload.setExpiresAt(1_783_000_000L);
    return payload;
  }
}
