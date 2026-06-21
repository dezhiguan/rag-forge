package com.ragforge.pipeline.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.mapper.DocumentMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.pipeline.indexer.EsIndexService;
import com.ragforge.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
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
  @Mock private ObjectStorage objectStorage;
  @Mock private ImagePipelineSupport imagePipelineSupport;
  @Mock private EsIndexService esIndexService;
  @Mock private JdbcTemplate jdbcTemplate;

  private ImagePipelineService service;
  private MultimodalProperties multimodalProperties;

  @BeforeEach
  void setUp() {
    multimodalProperties = new MultimodalProperties();
    service =
        new ImagePipelineService(
            documentMapper,
            objectStorage,
            imagePipelineSupport,
            esIndexService,
            jdbcTemplate,
            multimodalProperties);
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
    DocumentChunk noOcr = new DocumentChunk();
    noOcr.setChunkModality(ChunkModality.IMAGE_NO_OCR);
    DocumentChunk desc = new DocumentChunk();
    desc.setChunkModality(ChunkModality.IMAGE_DESC);
    when(imagePipelineSupport.processStandaloneImage(any(), any(), any(), any(Integer.class), any()))
        .thenReturn(List.of(noOcr, desc));
    doNothing().when(jdbcTemplate).query(any(PreparedStatementCreator.class), any(RowCallbackHandler.class));

    service.processImageDocument(7L);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<DocumentChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
    verify(esIndexService).indexChunks(chunksCaptor.capture(), any(Document.class));
    List<DocumentChunk> chunks = chunksCaptor.getValue();
    assertThat(chunks).extracting(DocumentChunk::getChunkModality)
        .containsExactly(ChunkModality.IMAGE_NO_OCR, ChunkModality.IMAGE_DESC);
    verify(jdbcTemplate).update("DELETE FROM document_chunks WHERE doc_id = ?", 7L);
    verify(jdbcTemplate).update("UPDATE documents SET chunk_count = ?, updated_at = NOW() WHERE id = ?", 2, 7L);
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
}
