package com.ragforge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.enums.SqlKeyword;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragforge.common.BizException;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.model.vo.DocumentDetailVO;
import com.ragforge.mq.DocumentProcessProducer;
import com.ragforge.pipeline.indexer.EsIndexService;
import com.ragforge.service.FileStorageService;
import com.ragforge.storage.ObjectMeta;
import com.ragforge.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class DocumentServiceImplTest {

  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private DocumentMapper documentMapper;
  @Mock private DocumentChunkMapper documentChunkMapper;
  @Mock private FileStorageService fileStorageService;
  @Mock private ObjectStorage objectStorage;
  @Mock private DocumentProcessProducer documentProcessProducer;
  @Mock private EsIndexService esIndexService;
  @Mock private com.ragforge.search.QdrantVectorStore qdrantVectorStore;

  // 真实 ObjectMapper：容器聚合需解析 expand_summary，@InjectMocks 会注入 @Spy。
  @Spy private ObjectMapper objectMapper = new ObjectMapper();

  @InjectMocks private DocumentServiceImpl documentService;

  private KnowledgeBase kb;

  @BeforeEach
  void setUp() {
    kb = activeKb(1L);
  }

  @Test
  void upload_newFile_storesAndEnqueuesProcessing() {
    when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);
    when(documentMapper.selectOne(any())).thenReturn(null);
    when(fileStorageService.store(any())).thenReturn("/data/new.pdf");

    MockMultipartFile file =
        new MockMultipartFile("file", "resume.pdf", "application/pdf", "pdf-bytes".getBytes());

    var result = documentService.upload(1L, file);

    assertThat(result.isExists()).isFalse();
    assertThat(result.getStatus()).isEqualTo("PROCESSING");
    assertThat(result.getDocument().getFilename()).isEqualTo("resume.pdf");
    verify(documentMapper).insert(any(Document.class));
    verify(documentProcessProducer).send(any());
  }

  @Test
  void upload_duplicateWithoutOverwrite_returnsExisting() {
    when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);
    Document existing = doc(9L, 1L, "old.pdf", "COMPLETED");
    when(documentMapper.selectOne(any())).thenReturn(existing);

    MockMultipartFile file =
        new MockMultipartFile("file", "old.pdf", "application/pdf", "pdf-bytes".getBytes());

    var result = documentService.upload(1L, file, false);

    assertThat(result.isExists()).isTrue();
    assertThat(result.getExistingDocument().getId()).isEqualTo(9L);
    verify(documentMapper, never()).insert(any(Document.class));
    verify(documentProcessProducer, never()).send(any());
  }

  @Test
  void upload_duplicateWithOverwrite_replacesDocument() {
    when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);
    Document existing = doc(9L, 1L, "old.pdf", "COMPLETED");
    existing.setFilePath("/data/old.pdf");
    existing.setChunkCount(3);
    existing.setVersion(2);
    when(documentMapper.selectOne(any())).thenReturn(existing);
    when(documentMapper.selectById(9L)).thenReturn(existing);
    when(fileStorageService.store(any())).thenReturn("/data/new.pdf");

    MockMultipartFile file =
        new MockMultipartFile("file", "new.pdf", "application/pdf", "new-bytes".getBytes());

    var result = documentService.upload(1L, file, true);

    assertThat(result.isExists()).isFalse();
    assertThat(result.getMessage()).contains("覆盖");
    verify(documentChunkMapper).delete(any());
    verify(esIndexService).deleteByDocId(9L);
    verify(documentProcessProducer).send(9L);
  }

  @Test
  void upload_rejectsEmptyOrUnsupportedFile() {
    when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

    assertThatThrownBy(
            () ->
                documentService.upload(
                    1L,
                    new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0])))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("不能为空");

    assertThatThrownBy(
            () ->
                documentService.upload(
                    1L, new MockMultipartFile("file", "bad.exe", "application/octet-stream", "x".getBytes())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("不支持的文件格式");
  }

  @Test
  void upload_rejectsArchiveWithFriendlyHint() {
    when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);
    // 压缩包直传应给出明确指引，而非笼统"不支持的文件格式"
    assertThatThrownBy(
            () ->
                documentService.upload(
                    1L, new MockMultipartFile("file", "bundle.zip", "application/zip", "PK".getBytes())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("压缩包");
  }

  @Test
  void upload_rejectsMismatchedMimeUnlessOctetStream() {
    when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

    assertThatThrownBy(
            () ->
                documentService.upload(
                    1L,
                    new MockMultipartFile("file", "a.pdf", "text/plain", "x".getBytes())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("MIME");

    MockMultipartFile tolerated =
        new MockMultipartFile("file", "a.pdf", "application/octet-stream", "x".getBytes());
    when(documentMapper.selectOne(any())).thenReturn(null);
    when(fileStorageService.store(any())).thenReturn("/data/a.pdf");
    assertThat(documentService.upload(1L, tolerated).isExists()).isFalse();
  }

  @Test
  void replaceDocument_updatesExistingDocument() {
    when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);
    Document existing = doc(5L, 1L, "old.md", "COMPLETED");
    existing.setFilePath("/data/old.md");
    existing.setChunkCount(2);
    when(documentMapper.selectById(5L)).thenReturn(existing);
    when(fileStorageService.store(any())).thenReturn("/data/new.md");

    MockMultipartFile file =
        new MockMultipartFile("file", "new.md", "text/markdown", "# title".getBytes());

    var vo = documentService.replaceDocument(1L, file, 5L);

    assertThat(vo.getFilename()).isEqualTo("new.md");
    assertThat(vo.getVersion()).isEqualTo(existing.getVersion());
    verify(knowledgeBaseMapper).updateById(any(KnowledgeBase.class));
  }

  @Test
  void replaceDocument_missingDocument_throws404() {
    when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);
    when(documentMapper.selectById(99L)).thenReturn(null);

    MockMultipartFile file =
        new MockMultipartFile("file", "a.md", "text/markdown", "x".getBytes());

    assertThatThrownBy(() -> documentService.replaceDocument(1L, file, 99L))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(404));
  }

  @Test
  void listByKbAndGetById_returnMappedViews() {
    when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);
    Document doc = doc(3L, 1L, "a.pdf", "COMPLETED");
    when(documentMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> {
      Page<Document> page = inv.getArgument(0);
      page.setRecords(List.of(doc));
      page.setTotal(1);
      return page;
    });
    when(documentMapper.selectById(3L)).thenReturn(doc);
    when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

    assertThat(documentService.listByKb(1L, 1, 10, null).getList()).hasSize(1);
    assertThat(documentService.getById(3L).getKbName()).isEqualTo("kb-1");
  }

  @Test
  void listChunks_capsPageSizeAndMapsRows() {
    Document doc = doc(3L, 1L, "a.pdf", "COMPLETED");
    when(documentMapper.selectById(3L)).thenReturn(doc);
    DocumentChunk chunk = new DocumentChunk();
    chunk.setId(30L);
    chunk.setChunkIndex(0);
    chunk.setContent("chunk");
    chunk.setTokenCount(4);
    when(documentChunkMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> {
      Page<DocumentChunk> page = inv.getArgument(0);
      page.setRecords(List.of(chunk));
      page.setTotal(1);
      return page;
    });

    var page = documentService.listChunks(3L, 1, 500, null);

    assertThat(page.getSize()).isEqualTo(100);
    assertThat(page.getList().get(0).getContent()).isEqualTo("chunk");
  }

  @Test
  @SuppressWarnings("unchecked")
  void listChunks_withKeyword_appliesTrimmedContentLikeFilter() {
    Document doc = doc(3L, 1L, "a.pdf", "COMPLETED");
    when(documentMapper.selectById(3L)).thenReturn(doc);
    when(documentChunkMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> {
      Page<DocumentChunk> page = inv.getArgument(0);
      page.setRecords(List.of());
      page.setTotal(0);
      return page;
    });
    ArgumentCaptor<Wrapper<DocumentChunk>> captor = ArgumentCaptor.forClass(Wrapper.class);

    documentService.listChunks(3L, 1, 20, "  hello  ");

    verify(documentChunkMapper).selectPage(any(Page.class), captor.capture());
    assertThat(hasLikeSegment(captor.getValue())).isTrue(); // 去空格后 content 加了 LIKE 条件
  }

  @Test
  @SuppressWarnings("unchecked")
  void listChunks_blankKeyword_appliesNoContentFilter() {
    Document doc = doc(3L, 1L, "a.pdf", "COMPLETED");
    when(documentMapper.selectById(3L)).thenReturn(doc);
    when(documentChunkMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> {
      Page<DocumentChunk> page = inv.getArgument(0);
      page.setRecords(List.of());
      page.setTotal(0);
      return page;
    });
    ArgumentCaptor<Wrapper<DocumentChunk>> captor = ArgumentCaptor.forClass(Wrapper.class);

    documentService.listChunks(3L, 1, 20, "   ");

    verify(documentChunkMapper).selectPage(any(Page.class), captor.capture());
    // 仅空白 → trim 后为空 → 不加 LIKE（证明了 trim 生效）
    assertThat(hasLikeSegment(captor.getValue())).isFalse();
  }

  @Test
  void listChunks_documentNotFound_throwsBizException() {
    when(documentMapper.selectById(99L)).thenReturn(null);

    assertThatThrownBy(() -> documentService.listChunks(99L, 1, 20, "any"))
        .isInstanceOf(BizException.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void listChunks_hashIndexKeyword_locatesByChunkIndexNotContent() {
    Document doc = doc(3L, 1L, "a.pdf", "COMPLETED");
    when(documentMapper.selectById(3L)).thenReturn(doc);
    when(documentChunkMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> {
      Page<DocumentChunk> page = inv.getArgument(0);
      page.setRecords(List.of());
      page.setTotal(0);
      return page;
    });
    ArgumentCaptor<Wrapper<DocumentChunk>> captor = ArgumentCaptor.forClass(Wrapper.class);

    documentService.listChunks(3L, 1, 20, "#5");

    verify(documentChunkMapper).selectPage(any(Page.class), captor.capture());
    // #编号 定位：按 chunk_index 精确匹配（doc_id + chunk_index 两个 = 段），不加 content LIKE
    assertThat(hasLikeSegment(captor.getValue())).isFalse();
    assertThat(countSqlKeyword(captor.getValue(), "=")).isEqualTo(2L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void listChunks_oversizedHashNumber_fallsBackToContentLike() {
    Document doc = doc(3L, 1L, "a.pdf", "COMPLETED");
    when(documentMapper.selectById(3L)).thenReturn(doc);
    when(documentChunkMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> {
      Page<DocumentChunk> page = inv.getArgument(0);
      page.setRecords(List.of());
      page.setTotal(0);
      return page;
    });
    ArgumentCaptor<Wrapper<DocumentChunk>> captor = ArgumentCaptor.forClass(Wrapper.class);

    // 超过 9 位 → 不当作编号定位（避免 int 溢出），退回按内容模糊搜
    documentService.listChunks(3L, 1, 20, "#12345678901");

    verify(documentChunkMapper).selectPage(any(Page.class), captor.capture());
    assertThat(hasLikeSegment(captor.getValue())).isTrue();
  }

  /** 检查查询条件里是否包含 LIKE 关键词段（只调 SqlKeyword 段，避免触发列名解析需要 TableInfo）。 */
  private static boolean hasLikeSegment(Wrapper<DocumentChunk> wrapper) {
    return ((AbstractWrapper<?, ?, ?>) wrapper)
        .getExpression().getNormal().stream()
            .filter(s -> s instanceof SqlKeyword)
            .anyMatch(s -> "LIKE".equals(s.getSqlSegment()));
  }

  /** 统计查询条件里某个 SqlKeyword（如 "="）出现的次数。 */
  private static long countSqlKeyword(Wrapper<DocumentChunk> wrapper, String sql) {
    return ((AbstractWrapper<?, ?, ?>) wrapper)
        .getExpression().getNormal().stream()
            .filter(s -> s instanceof SqlKeyword)
            .filter(s -> sql.equals(s.getSqlSegment()))
            .count();
  }

  @Test
  void getStatus_returnsParseInfo() {
    Document doc = doc(4L, 1L, "a.pdf", "FAILED");
    doc.setErrorMsg("parse error");
    doc.setChunkCount(0);
    when(documentMapper.selectById(4L)).thenReturn(doc);

    var status = documentService.getStatus(4L);

    assertThat(status.getParseStatus()).isEqualTo("FAILED");
    assertThat(status.getErrorMsg()).isEqualTo("parse error");
  }

  @Test
  void download_returnsAttachment(@TempDir Path tempDir) throws Exception {
    Path file = tempDir.resolve("doc.pdf");
    Files.writeString(file, "bytes", StandardCharsets.UTF_8);
    Document doc = doc(8L, 1L, "doc.pdf", "COMPLETED");
    doc.setFilePath(file.toString());
    when(documentMapper.selectById(8L)).thenReturn(doc);

    ResponseEntity<?> response = documentService.download(8L);

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("doc.pdf");
  }

  @Test
  void download_readsFromObjectStorageWhenBucketPresent() {
    Document doc = doc(9L, 1L, "doc.pdf", "COMPLETED");
    doc.setStorageBucket("rag-test-bucket");
    doc.setFilePath("tenant-1/kb_1/uplt/test/doc.pdf");
    ObjectMeta meta = new ObjectMeta();
    meta.setSizeBytes(12L);

    when(documentMapper.selectById(9L)).thenReturn(doc);
    when(objectStorage.head("rag-test-bucket", "tenant-1/kb_1/uplt/test/doc.pdf")).thenReturn(meta);
    when(objectStorage.get("rag-test-bucket", "tenant-1/kb_1/uplt/test/doc.pdf"))
        .thenReturn(new ByteArrayInputStream("oss-file-bytes".getBytes(StandardCharsets.UTF_8)));

    ResponseEntity<?> response = documentService.download(9L);

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("doc.pdf");
    assertThat(response.getHeaders().getContentLength()).isEqualTo(12);
  }

  @Test
  void download_missingObjectStorageObject_throws404() {
    Document doc = doc(10L, 1L, "doc.pdf", "COMPLETED");
    doc.setStorageBucket("rag-test-bucket");
    doc.setFilePath("tenant-1/kb_1/uplt/missing.doc.pdf");

    when(documentMapper.selectById(10L)).thenReturn(doc);
    when(objectStorage.head("rag-test-bucket", "tenant-1/kb_1/uplt/missing.doc.pdf")).thenReturn(null);

    assertThatThrownBy(() -> documentService.download(10L))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("原文件不存在");
  }

  @Test
  void download_missingFile_throws404() {
    Document doc = doc(8L, 1L, "doc.pdf", "COMPLETED");
    doc.setFilePath("/missing/doc.pdf");
    when(documentMapper.selectById(8L)).thenReturn(doc);

    assertThatThrownBy(() -> documentService.download(8L))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("原文件不存在");
  }

  @Test
  void reprocess_onlyFailedDocuments() {
    Document completed = doc(7L, 1L, "b.pdf", "COMPLETED");
    when(documentMapper.selectById(7L)).thenReturn(completed);
    assertThatThrownBy(() -> documentService.reprocess(7L))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("失败状态");
    verify(documentProcessProducer, never()).send(any());
  }

  @Test
  void delete_removesArtifactsAndUpdatesKbCounters() {
    Document doc = doc(10L, 1L, "done.pdf", "COMPLETED");
    doc.setFilePath("/data/done.pdf");
    doc.setChunkCount(5);
    kb.setDocCount(2);
    kb.setChunkCount(10);
    when(documentMapper.selectById(10L)).thenReturn(doc);
    when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

    documentService.delete(10L);

    verify(esIndexService).deleteByDocId(10L);
    verify(fileStorageService).delete("/data/done.pdf");
    verify(documentMapper).deleteById(10L);
    ArgumentCaptor<KnowledgeBase> captor = ArgumentCaptor.forClass(KnowledgeBase.class);
    verify(knowledgeBaseMapper).updateById(captor.capture());
    assertThat(captor.getValue().getDocCount()).isEqualTo(1);
    assertThat(captor.getValue().getChunkCount()).isEqualTo(5);
  }

  @Test
  void requireActiveKb_rejectsDeletedKnowledgeBase() {
    KnowledgeBase deleted = activeKb(2L);
    deleted.setStatus("deleted");
    when(knowledgeBaseMapper.selectById(2L)).thenReturn(deleted);

    assertThatThrownBy(
            () ->
                documentService.upload(
                    2L,
                    new MockMultipartFile("file", "a.pdf", "application/pdf", "x".getBytes())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("知识库不存在");
  }

  @Test
  void getById_archiveContainer_withSkips_aggregatesChildrenSummary() {
    Document container = archiveDoc(20L, 1L, "bundle.zip", "zip");
    container.setExpandSummary(
        "{\"totalEntries\":6,\"registered\":4,\"skipped\":["
            + "{\"path\":\"a/b.exe\",\"reason\":\"not_whitelisted\"},"
            + "{\"path\":\"big.pdf\"}]}");
    when(documentMapper.selectById(20L)).thenReturn(container);
    when(documentMapper.countChildrenByStatus(20L))
        .thenReturn(
            List.of(
                Map.of("status", "COMPLETED", "cnt", 3L),
                Map.of("status", "FAILED", "cnt", 1L),
                Map.of("status", "PENDING", "cnt", 1),
                Map.of("status", "PROCESSING", "cnt", 2L),
                // 未知状态仍计入 total，但不单列
                Map.of("status", "EXPANDED", "cnt", 1L)));

    DocumentDetailVO vo = documentService.getById(20L);

    assertThat(vo.getIsArchive()).isTrue();
    var summary = vo.getChildrenSummary();
    assertThat(summary).isNotNull();
    assertThat(summary.getCompleted()).isEqualTo(3);
    assertThat(summary.getFailed()).isEqualTo(1);
    assertThat(summary.getPending()).isEqualTo(1);
    assertThat(summary.getProcessing()).isEqualTo(2);
    assertThat(summary.getTotal()).isEqualTo(8); // 3+1+1+2+1
    assertThat(summary.getSkipped()).isEqualTo(2);
    assertThat(vo.getSkippedEntries()).hasSize(2);
    assertThat(vo.getSkippedEntries().get(0).getPath()).isEqualTo("a/b.exe");
    assertThat(vo.getSkippedEntries().get(0).getReason()).isEqualTo("not_whitelisted");
    // 缺失 reason 字段容忍为 null
    assertThat(vo.getSkippedEntries().get(1).getReason()).isNull();
  }

  @Test
  void getById_archiveContainer_nullExpandSummary_hasEmptySkips() {
    Document container = archiveDoc(21L, 1L, "bundle.tar.gz", "tar.gz");
    container.setExpandSummary(null);
    when(documentMapper.selectById(21L)).thenReturn(container);
    when(documentMapper.countChildrenByStatus(21L))
        .thenReturn(List.of(Map.of("status", "COMPLETED", "cnt", 5L)));

    DocumentDetailVO vo = documentService.getById(21L);

    assertThat(vo.getIsArchive()).isTrue();
    assertThat(vo.getChildrenSummary().getTotal()).isEqualTo(5);
    assertThat(vo.getChildrenSummary().getSkipped()).isZero();
    assertThat(vo.getSkippedEntries()).isEmpty();
  }

  @Test
  void getById_archiveContainer_invalidExpandSummary_toleratesAndReturnsEmptySkips() {
    Document container = archiveDoc(22L, 1L, "broken.zip", "zip");
    container.setExpandSummary("not-json");
    when(documentMapper.selectById(22L)).thenReturn(container);
    when(documentMapper.countChildrenByStatus(22L)).thenReturn(List.of());

    DocumentDetailVO vo = documentService.getById(22L);

    assertThat(vo.getIsArchive()).isTrue();
    assertThat(vo.getChildrenSummary().getTotal()).isZero();
    assertThat(vo.getSkippedEntries()).isEmpty();
  }

  @Test
  void getById_normalDocument_isArchiveFalseAndNoAggregation() {
    Document normal = doc(23L, 1L, "resume.pdf", "COMPLETED");
    when(documentMapper.selectById(23L)).thenReturn(normal);

    DocumentDetailVO vo = documentService.getById(23L);

    assertThat(vo.getIsArchive()).isFalse();
    assertThat(vo.getChildrenSummary()).isNull();
    assertThat(vo.getSkippedEntries()).isNull();
    verify(documentMapper, never()).countChildrenByStatus(any());
  }

  @Test
  void listChildren_returnsMappedChildren() {
    Document container = archiveDoc(30L, 1L, "bundle.zip", "zip");
    Document child1 = doc(31L, 1L, "a.pdf", "COMPLETED");
    child1.setParentDocumentId(30L);
    Document child2 = doc(32L, 1L, "b.md", "PENDING");
    child2.setParentDocumentId(30L);
    when(documentMapper.selectById(30L)).thenReturn(container);
    when(documentMapper.selectChildren(30L)).thenReturn(List.of(child1, child2));

    var children = documentService.listChildren(30L);

    assertThat(children).hasSize(2);
    assertThat(children.get(0).getId()).isEqualTo(31L);
    assertThat(children.get(1).getFilename()).isEqualTo("b.md");
  }

  @Test
  void listChildren_containerNotFound_throws404() {
    when(documentMapper.selectById(99L)).thenReturn(null);

    assertThatThrownBy(() -> documentService.listChildren(99L))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(404));
  }

  @Test
  void listChildren_notAContainer_throws404() {
    Document normal = doc(40L, 1L, "resume.pdf", "COMPLETED");
    when(documentMapper.selectById(40L)).thenReturn(normal);

    assertThatThrownBy(() -> documentService.listChildren(40L))
        .isInstanceOf(BizException.class)
        .satisfies(ex -> assertThat(((BizException) ex).getCode()).isEqualTo(404));
    verify(documentMapper, never()).selectChildren(any());
  }

  private static Document archiveDoc(long id, long kbId, String filename, String fileType) {
    Document doc = doc(id, kbId, filename, "EXPANDED");
    doc.setFileType(fileType);
    doc.setParentDocumentId(null);
    return doc;
  }

  private static KnowledgeBase activeKb(long id) {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(id);
    kb.setName("kb-" + id);
    kb.setStatus("active");
    kb.setDocCount(1);
    kb.setChunkCount(4);
    kb.setChunkSize(500);
    kb.setChunkOverlap(50);
    kb.setEmbeddingModel("qwen3-vl-embedding");
    return kb;
  }

  private static Document doc(long id, long kbId, String filename, String status) {
    Document doc = new Document();
    doc.setId(id);
    doc.setKbId(kbId);
    doc.setFilename(filename);
    doc.setFilePath("/data/" + filename);
    doc.setFileSize(10L);
    doc.setFileType("application/pdf");
    doc.setFileMd5("abc");
    doc.setVersion(1);
    doc.setParseStatus(status);
    doc.setChunkCount(1);
    doc.setCreatedAt(LocalDateTime.now());
    return doc;
  }
}
