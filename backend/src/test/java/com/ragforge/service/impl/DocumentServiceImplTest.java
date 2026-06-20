package com.ragforge.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragforge.common.BizException;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.mq.DocumentProcessProducer;
import com.ragforge.pipeline.indexer.EsIndexService;
import com.ragforge.service.FileStorageService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class DocumentServiceImplTest {

  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private DocumentMapper documentMapper;
  @Mock private DocumentChunkMapper documentChunkMapper;
  @Mock private FileStorageService fileStorageService;
  @Mock private DocumentProcessProducer documentProcessProducer;
  @Mock private EsIndexService esIndexService;

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
                    1L, new MockMultipartFile("file", "bad.txt", "text/plain", "x".getBytes())))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("只允许");
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

    assertThat(documentService.listByKb(1L, 1, 10).getList()).hasSize(1);
    assertThat(documentService.getById(3L).getKbName()).isEqualTo("kb-1");
  }

  @Test
  void listChunks_capsPageSizeAndMapsRows() {
    Document doc = doc(3L, 1L, "a.pdf", "COMPLETED");
    when(documentMapper.selectById(3L)).thenReturn(doc);
    DocumentChunk chunk = new DocumentChunk();
    chunk.setChunkIndex(0);
    chunk.setContent("chunk");
    chunk.setTokenCount(4);
    when(documentChunkMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> {
      Page<DocumentChunk> page = inv.getArgument(0);
      page.setRecords(List.of(chunk));
      page.setTotal(1);
      return page;
    });

    var page = documentService.listChunks(3L, 1, 500);

    assertThat(page.getSize()).isEqualTo(100);
    assertThat(page.getList().get(0).getContent()).isEqualTo("chunk");
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

  private static KnowledgeBase activeKb(long id) {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(id);
    kb.setName("kb-" + id);
    kb.setStatus("active");
    kb.setDocCount(1);
    kb.setChunkCount(4);
    kb.setChunkSize(500);
    kb.setChunkOverlap(50);
    kb.setEmbeddingModel("text-embedding-v4");
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
