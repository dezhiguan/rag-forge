package com.ragforge.pipeline.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.common.BizException;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.pipeline.indexer.EsIndexService;
import com.ragforge.storage.ObjectStorage;
import com.pgvector.PGvector;
import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;

@ExtendWith(MockitoExtension.class)
class ImagePipelineServiceTest {

  @Mock private DocumentMapper documentMapper;
  @Mock private com.ragforge.mapper.KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private ObjectStorage objectStorage;
  @Mock private ImagePipelineSupport imagePipelineSupport;
  @Mock private EsIndexService esIndexService;
  @Mock private JdbcTemplate jdbcTemplate;
  @Mock private com.ragforge.search.QdrantVectorStore qdrantVectorStore;

  private ImagePipelineService service;
  private MultimodalProperties multimodalProperties;

  @BeforeEach
  void setUp() {
    multimodalProperties = new MultimodalProperties();
    service =
        new ImagePipelineService(
            documentMapper,
            knowledgeBaseMapper,
            objectStorage,
            imagePipelineSupport,
            esIndexService,
            jdbcTemplate,
            multimodalProperties,
            qdrantVectorStore);
  }

  @Test
  void processImageDocumentContinuesWhenOcrFails() throws Exception {
    Document doc = new Document();
    doc.setId(7L);
    doc.setKbId(3L);
    doc.setFilename("arch.png");
    doc.setFileType("image/png");
    doc.setStorageBucket("bucket");
    doc.setStorageKey("tenant/kb/arch.png");
    when(documentMapper.selectById(7L)).thenReturn(doc);
    when(objectStorage.get("bucket", "tenant/kb/arch.png"))
        .thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));
    DocumentChunk image = new DocumentChunk();
    image.setChunkModality(ChunkModality.IMAGE);
    when(imagePipelineSupport.processStandaloneImage(any(), any(), any(), any(Integer.class), any()))
        .thenReturn(List.of(image));
    doNothing().when(jdbcTemplate).query(any(PreparedStatementCreator.class), any(RowCallbackHandler.class));

    service.processImageDocument(7L);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<DocumentChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
    verify(esIndexService).indexChunks(chunksCaptor.capture(), any(Document.class));
    List<DocumentChunk> chunks = chunksCaptor.getValue();
    assertThat(chunks).extracting(DocumentChunk::getChunkModality)
        .containsExactly(ChunkModality.IMAGE);
    verify(jdbcTemplate).update("DELETE FROM document_chunks WHERE doc_id = ?", 7L);
    verify(jdbcTemplate).update("UPDATE documents SET chunk_count = ?, updated_at = NOW() WHERE id = ?", 1, 7L);
    verify(documentMapper).selectById(7L);
  }

  @Test
  void processImageDocumentSkipsWhenMultimodalDisabled() {
    multimodalProperties.setEnabled(false);
    Document doc = new Document();
    doc.setId(8L);
    doc.setKbId(3L);
    doc.setFilename("arch.png");
    doc.setFileType("image/png");
    when(documentMapper.selectById(8L)).thenReturn(doc);

    service.processImageDocument(8L);

    verify(jdbcTemplate).update("DELETE FROM document_chunks WHERE doc_id = ?", 8L);
    verify(jdbcTemplate).update("UPDATE documents SET chunk_count = ?, updated_at = NOW() WHERE id = ?", 0, 8L);
    verify(esIndexService).deleteByDocId(8L);
    verify(esIndexService, org.mockito.Mockito.never()).indexChunks(any(), any());
  }

  @Test
  void processImageDocument_readsLocalFileWhenNoStorageBucket(@org.junit.jupiter.api.io.TempDir Path tempDir)
      throws Exception {
    Path image = tempDir.resolve("local.png");
    Files.write(image, new byte[] {9, 8, 7});
    Document doc = new Document();
    doc.setId(9L);
    doc.setKbId(3L);
    doc.setFileType("image/png");
    doc.setFilePath(image.toString());
    when(documentMapper.selectById(9L)).thenReturn(doc);
    DocumentChunk chunk = new DocumentChunk();
    chunk.setDocId(9L);
    chunk.setKbId(3L);
    chunk.setChunkIndex(0);
    chunk.setContent("local image");
    chunk.setVlVector(new PGvector(new float[] {0.1f, 0.2f}));
    chunk.setTokenCount(2);
    chunk.setChunkType("IMAGE");
    chunk.setChunkModality(ChunkModality.IMAGE);
    chunk.setChunkMetadataJson("{}");
    when(imagePipelineSupport.processStandaloneImage(any(byte[].class), eq("image/png"), eq(doc), eq(0), anyString()))
        .thenReturn(List.of(chunk));
    doNothing().when(jdbcTemplate).query(any(PreparedStatementCreator.class), any(RowCallbackHandler.class));

    service.processImageDocument(9L);

    verify(objectStorage, org.mockito.Mockito.never()).get(any(), any());
    verify(esIndexService).indexChunks(any(), eq(doc));
    verify(jdbcTemplate).update("UPDATE documents SET chunk_count = ?, updated_at = NOW() WHERE id = ?", 1, 9L);
  }

  @Test
  void insertImageChunks_emptyInputReturnsEmptyWithoutJdbcQuery() {
    assertThat(service.insertImageChunks(null)).isEmpty();
    assertThat(service.insertImageChunks(List.of())).isEmpty();

    verify(jdbcTemplate, org.mockito.Mockito.never())
        .query(any(PreparedStatementCreator.class), any(RowCallbackHandler.class));
  }

  @Test
  void insertImageChunks_assignsReturnedIdsAndCreatedAt() throws Exception {
    DocumentChunk chunk = new DocumentChunk();
    chunk.setDocId(11L);
    chunk.setKbId(3L);
    chunk.setChunkIndex(4);
    chunk.setContent("image text");
    chunk.setVlVector(new PGvector(new float[] {0.1f}));
    chunk.setTokenCount(2);
    chunk.setChunkType("IMAGE");
    chunk.setChunkModality(ChunkModality.IMAGE);
    chunk.setChunkMetadataJson("{}");
    chunk.setImageKey("images/11.png");
    doAnswerQueryReturning(999L, 4);

    List<DocumentChunk> inserted = service.insertImageChunks(List.of(chunk));

    assertThat(inserted).containsExactly(chunk);
    assertThat(chunk.getId()).isEqualTo(999L);
    assertThat(chunk.getCreatedAt()).isNotNull();
  }

  @Test
  void processImageDocument_objectStorageFailureMarksFailedAndRethrows() {
    Document doc = new Document();
    doc.setId(12L);
    doc.setKbId(3L);
    doc.setFileType("image/png");
    doc.setStorageBucket("bucket");
    doc.setStorageKey("tenant/3/key.png");
    when(documentMapper.selectById(12L)).thenReturn(doc);
    when(objectStorage.get("bucket", "tenant/3/key.png")).thenThrow(new RuntimeException("oss down"));

    assertThatThrownBy(() -> service.processImageDocument(12L))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("oss down");
    verify(jdbcTemplate).update(contains("UPDATE documents"), eq("FAILED"), contains("oss down"), eq(12L));
  }

  @Test
  void updateStatus_truncatesLongErrorMessage() {
    String longMessage = "x".repeat(2100);

    service.updateStatus(77L, "FAILED", longMessage);

    verify(jdbcTemplate)
        .update(contains("UPDATE documents"), eq("FAILED"), eq("x".repeat(2000)), eq(77L));
  }

  @Test
  void processImageDocument_missingDocumentMarksFailedAndRethrows() {
    when(documentMapper.selectById(404L)).thenReturn(null);

    assertThatThrownBy(() -> service.processImageDocument(404L))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("文档不存在: 404");

    verify(jdbcTemplate)
        .update(contains("UPDATE documents"), eq("FAILED"), contains("文档不存在: 404"), eq(404L));
  }

  private void doAnswerQueryReturning(long id, int chunkIndex) {
    org.mockito.Mockito.doAnswer(
            inv -> {
              PreparedStatementCreator psc = inv.getArgument(0);
              RowCallbackHandler rch = inv.getArgument(1);
              Connection conn = org.mockito.Mockito.mock(Connection.class);
              PreparedStatement ps = org.mockito.Mockito.mock(PreparedStatement.class);
              ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
              when(conn.prepareStatement(anyString())).thenReturn(ps);
              when(rs.getInt("chunk_index")).thenReturn(chunkIndex);
              when(rs.getLong("id")).thenReturn(id);
              psc.createPreparedStatement(conn);
              rch.processRow(rs);
              return null;
            })
        .when(jdbcTemplate)
        .query(any(PreparedStatementCreator.class), any(RowCallbackHandler.class));
  }
}
