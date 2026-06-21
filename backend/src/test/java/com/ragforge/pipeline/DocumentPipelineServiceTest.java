package com.ragforge.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.pipeline.chunker.Chunk;
import com.ragforge.pipeline.chunker.ChunkParams;
import com.ragforge.pipeline.chunker.ChunkingResult;
import com.ragforge.pipeline.chunker.ChunkingService;
import com.ragforge.pipeline.embedder.EmbeddingService;
import com.ragforge.pipeline.image.EmbeddedImageExtractor;
import com.ragforge.pipeline.image.ExtractedImage;
import com.ragforge.pipeline.image.ImageChunkContext;
import com.ragforge.pipeline.image.ImagePipelineService;
import com.ragforge.pipeline.image.ImagePipelineSupport;
import com.ragforge.pipeline.indexer.EsIndexService;
import com.ragforge.pipeline.cleaner.CleanProfile;
import com.ragforge.pipeline.cleaner.CleanProfileService;
import com.ragforge.pipeline.cleaner.CleanResult;
import com.ragforge.pipeline.cleaner.CleaningPipeline;
import com.ragforge.pipeline.cleaner.RawText;
import com.ragforge.pipeline.cleaner.ResolvedCleanProfile;
import com.ragforge.pipeline.parser.DocumentParser;
import com.ragforge.pipeline.parser.ParseResult;
import com.ragforge.storage.ObjectStorage;
import java.util.Map;
import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentPipelineServiceTest {

  @Mock private DocumentMapper documentMapper;
  @Mock private DocumentChunkMapper documentChunkMapper;
  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private DocumentParser documentParser;
  @Mock private ChunkingService chunkingService;
  @Mock private EmbeddingService embeddingService;
  @Mock private EsIndexService esIndexService;
  @Mock private JdbcTemplate jdbcTemplate;
  @Mock private ObjectStorage objectStorage;
  @Mock private CleaningPipeline cleaningPipeline;
  @Mock private CleanProfileService cleanProfileService;
  @Mock private ImagePipelineSupport imagePipelineSupport;
  @Mock private ImagePipelineService imagePipelineService;

  @Spy @InjectMocks private DocumentPipelineService documentPipelineService;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(documentPipelineService, "self", documentPipelineService);
    ReflectionTestUtils.setField(documentPipelineService, "objectMapper", new ObjectMapper());
    ReflectionTestUtils.setField(documentPipelineService, "embeddedImageExtractors", List.<EmbeddedImageExtractor>of());
    stubPipelineSideEffects();
    when(cleanProfileService.resolveForKb(anyLong()))
        .thenReturn(new ResolvedCleanProfile(null, new CleanProfile()));
    when(cleaningPipeline.clean(any(RawText.class), any(CleanProfile.class)))
        .thenAnswer(inv -> CleanResult.of(inv.<RawText>getArgument(0).getText()));
  }

  private void stubPipelineSideEffects() {
    doNothing().when(documentPipelineService).cleanupArtifacts(anyLong());
    doNothing().when(documentPipelineService).updateStatus(anyLong(), anyString());
    doNothing().when(documentPipelineService).updateStatusWithError(anyLong(), anyString(), anyString());
    doNothing().when(documentPipelineService).updateDocumentChunkCount(anyLong(), anyInt());
    doNothing().when(documentPipelineService).updateCleanReport(anyLong(), any(), anyString());
    doNothing().when(documentPipelineService).incrementKbCount(anyLong(), anyInt(), anyBoolean());
  }

  @Test
  void processDocument_successRunsAllStages() throws Exception {
    Document doc = document(1L, 10L, "sample.md");
    KnowledgeBase kb = knowledgeBase(10L);
    List<Chunk> chunks = List.of(new Chunk(0, "hello", 5), new Chunk(1, "world", 5));
    List<float[]> vectors = List.of(new float[] {0.1f}, new float[] {0.2f});
    List<DocumentChunk> inserted = List.of(chunkEntity(100L, 0), chunkEntity(101L, 1));

    when(documentMapper.selectById(1L)).thenReturn(doc);
    when(knowledgeBaseMapper.selectById(10L)).thenReturn(kb);
    when(documentParser.parse(doc.getFilePath(), doc.getFileType()))
        .thenReturn(new ParseResult("hello world", 1L, 1));
    when(chunkingService.split(eq(doc), eq(kb), eq("hello world")))
        .thenReturn(chunkingResult(chunks));
    when(embeddingService.embedBatch(List.of("hello", "world"))).thenReturn(vectors);
    doReturn(inserted)
        .when(documentPipelineService)
        .insertChunks(eq(1L), eq(10L), eq(chunks), eq(vectors), eq("RESUME"), eq("RECURSIVE"));
    when(esIndexService.indexChunks(inserted, doc)).thenReturn(true);

    documentPipelineService.processDocument(1L);

    verify(documentPipelineService).cleanupArtifacts(1L);
    verify(documentPipelineService, org.mockito.Mockito.times(4)).updateStatus(1L, "PROCESSING");
    verify(documentPipelineService).updateStatus(1L, "COMPLETED");
    verify(documentPipelineService).updateDocumentChunkCount(1L, 2);
    verify(documentPipelineService).updateCleanReport(eq(1L), eq(null), anyString());
    verify(documentPipelineService).incrementKbCount(10L, 2, true);
  }

  @Test
  void processDocument_usesCleanedTextBeforeChunking() throws Exception {
    Document doc = document(8L, 10L, "resume.md");
    KnowledgeBase kb = knowledgeBase(10L);
    List<Chunk> chunks = List.of(new Chunk(0, "电话：138****1234", 14));
    List<float[]> vectors = List.of(new float[] {0.1f});
    List<DocumentChunk> inserted = List.of(chunkEntity(600L, 0));

    when(documentMapper.selectById(8L)).thenReturn(doc);
    when(knowledgeBaseMapper.selectById(10L)).thenReturn(kb);
    when(documentParser.parse(anyString(), anyString())).thenReturn(new ParseResult("电话：13800138000", 1L, 1));
    when(cleaningPipeline.clean(any(RawText.class), any(CleanProfile.class)))
        .thenReturn(CleanResult.of("电话：138****1234"));
    when(chunkingService.split(eq(doc), eq(kb), eq("电话：138****1234")))
        .thenReturn(chunkingResult(chunks));
    when(embeddingService.embedBatch(List.of("电话：138****1234"))).thenReturn(vectors);
    doReturn(inserted)
        .when(documentPipelineService)
        .insertChunks(eq(8L), eq(10L), eq(chunks), eq(vectors), eq("RESUME"), eq("RECURSIVE"));
    when(esIndexService.indexChunks(inserted, doc)).thenReturn(true);

    documentPipelineService.processDocument(8L);

    verify(chunkingService).split(eq(doc), eq(kb), eq("电话：138****1234"));
    verify(embeddingService).embedBatch(List.of("电话：138****1234"));
  }

  @Test
  void processDocument_missingDocument_throwsAndMarksFailed() {
    when(documentMapper.selectById(404L)).thenReturn(null);

    assertThatThrownBy(() -> documentPipelineService.processDocument(404L))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("文档不存在");

    verify(documentPipelineService).updateStatusWithError(eq(404L), eq("FAILED"), anyString());
  }

  @Test
  void processDocument_embeddingMismatch_marksFailed() throws Exception {
    Document doc = document(2L, 10L, "a.md");
    when(documentMapper.selectById(2L)).thenReturn(doc);
    when(knowledgeBaseMapper.selectById(10L)).thenReturn(knowledgeBase(10L));
    when(documentParser.parse(anyString(), anyString())).thenReturn(new ParseResult("text", 1L, 1));
    when(chunkingService.split(any(Document.class), any(KnowledgeBase.class), anyString()))
        .thenReturn(chunkingResult(List.of(new Chunk(0, "text", 4))));
    when(embeddingService.embedBatch(anyList())).thenReturn(List.of());

    assertThatThrownBy(() -> documentPipelineService.processDocument(2L))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("Embedding 数量");

    verify(documentPipelineService).updateStatusWithError(eq(2L), eq("FAILED"), anyString());
  }

  @Test
  void processDocument_esIndexFailure_marksFailed() throws Exception {
    Document doc = document(3L, 10L, "a.md");
    List<Chunk> chunks = List.of(new Chunk(0, "text", 4));
    List<float[]> vectors = List.of(new float[] {0.1f});
    List<DocumentChunk> inserted = List.of(chunkEntity(200L, 0));

    when(documentMapper.selectById(3L)).thenReturn(doc);
    when(knowledgeBaseMapper.selectById(10L)).thenReturn(knowledgeBase(10L));
    when(documentParser.parse(anyString(), anyString())).thenReturn(new ParseResult("text", 1L, 1));
    when(chunkingService.split(any(Document.class), any(KnowledgeBase.class), anyString()))
        .thenReturn(chunkingResult(chunks));
    when(embeddingService.embedBatch(anyList())).thenReturn(vectors);
    doReturn(inserted)
        .when(documentPipelineService)
        .insertChunks(anyLong(), anyLong(), anyList(), anyList(), any(), anyString());
    when(esIndexService.indexChunks(inserted, doc)).thenReturn(false);

    assertThatThrownBy(() -> documentPipelineService.processDocument(3L))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("ES 索引写入失败");
  }

  @Test
  void processDocument_parserFailure_marksFailed() throws Exception {
    Document doc = document(4L, 10L, "bad.pdf");
    when(documentMapper.selectById(4L)).thenReturn(doc);
    when(knowledgeBaseMapper.selectById(10L)).thenReturn(knowledgeBase(10L));
    when(documentParser.parse(anyString(), anyString())).thenThrow(new RuntimeException("parse failed"));

    assertThatThrownBy(() -> documentPipelineService.processDocument(4L))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("parse failed");

    verify(documentPipelineService).updateStatusWithError(eq(4L), eq("FAILED"), anyString());
  }

  @Test
  void processDocument_reprocessDoesNotIncrementDocCount() throws Exception {
    Document doc = document(5L, 10L, "v2.md");
    doc.setVersion(2);
    doc.setParseStatus("PENDING");
    List<Chunk> chunks = List.of(new Chunk(0, "text", 4));
    List<float[]> vectors = List.of(new float[] {0.1f});
    List<DocumentChunk> inserted = List.of(chunkEntity(300L, 0));

    when(documentMapper.selectById(5L)).thenReturn(doc);
    when(knowledgeBaseMapper.selectById(10L)).thenReturn(knowledgeBase(10L));
    when(documentParser.parse(anyString(), anyString())).thenReturn(new ParseResult("text", 1L, 1));
    when(chunkingService.split(any(Document.class), any(KnowledgeBase.class), anyString()))
        .thenReturn(chunkingResult(chunks));
    when(embeddingService.embedBatch(anyList())).thenReturn(vectors);
    doReturn(inserted)
        .when(documentPipelineService)
        .insertChunks(anyLong(), anyLong(), anyList(), anyList(), any(), anyString());
    when(esIndexService.indexChunks(inserted, doc)).thenReturn(true);

    documentPipelineService.processDocument(5L);

    verify(documentPipelineService).incrementKbCount(10L, 1, false);
  }

  @Test
  void processDocument_indexedContentSkipsParserAndObjectRead() throws Exception {
    Document doc = document(6L, 10L, "page.html");
    KnowledgeBase kb = knowledgeBase(10L);
    doc.setFileType("text/html");
    doc.setStorageBucket("bucket");
    doc.setStorageKey("tn/kb/page.html");
    doc.setIndexedContent("干净文本");
    List<Chunk> chunks = List.of(new Chunk(0, "干净文本", 4));
    List<float[]> vectors = List.of(new float[] {0.1f});
    List<DocumentChunk> inserted = List.of(chunkEntity(400L, 0));

    when(documentMapper.selectById(6L)).thenReturn(doc);
    when(knowledgeBaseMapper.selectById(10L)).thenReturn(kb);
    when(chunkingService.split(eq(doc), eq(kb), eq("干净文本")))
        .thenReturn(chunkingResult(chunks));
    when(embeddingService.embedBatch(List.of("干净文本"))).thenReturn(vectors);
    doReturn(inserted)
        .when(documentPipelineService)
        .insertChunks(eq(6L), eq(10L), eq(chunks), eq(vectors), eq("RESUME"), eq("RECURSIVE"));
    when(esIndexService.indexChunks(inserted, doc)).thenReturn(true);

    documentPipelineService.processDocument(6L);

    verify(documentParser, never()).parse(anyString(), anyString());
    verify(objectStorage, never()).get(anyString(), anyString());
    verify(chunkingService).split(eq(doc), eq(kb), eq("干净文本"));
  }

  @Test
  void processDocument_textMimeReadsObjectAsTextWithoutParser() throws Exception {
    Document doc = document(7L, 10L, "page.html");
    KnowledgeBase kb = knowledgeBase(10L);
    doc.setFileType("text/html; charset=utf-8");
    doc.setStorageBucket("bucket");
    doc.setStorageKey("tn/kb/page.html");
    List<Chunk> chunks = List.of(new Chunk(0, "<h1>raw</h1>", 12));
    List<float[]> vectors = List.of(new float[] {0.1f});
    List<DocumentChunk> inserted = List.of(chunkEntity(500L, 0));

    when(documentMapper.selectById(7L)).thenReturn(doc);
    when(knowledgeBaseMapper.selectById(10L)).thenReturn(kb);
    when(objectStorage.get("bucket", "tn/kb/page.html"))
        .thenReturn(new ByteArrayInputStream("<h1>raw</h1>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    when(chunkingService.split(eq(doc), eq(kb), eq("<h1>raw</h1>")))
        .thenReturn(chunkingResult(chunks));
    when(embeddingService.embedBatch(List.of("<h1>raw</h1>"))).thenReturn(vectors);
    doReturn(inserted)
        .when(documentPipelineService)
        .insertChunks(eq(7L), eq(10L), eq(chunks), eq(vectors), eq("RESUME"), eq("RECURSIVE"));
    when(esIndexService.indexChunks(inserted, doc)).thenReturn(true);

    documentPipelineService.processDocument(7L);

    verify(documentParser, never()).parse(anyString(), anyString());
    verify(chunkingService).split(eq(doc), eq(kb), eq("<h1>raw</h1>"));
  }

  @Test
  void processDocument_imageProcessingOffSkipsEmbeddedImages() throws Exception {
    Document doc = document(9L, 10L, "mixed.pdf");
    KnowledgeBase kb = knowledgeBase(10L);
    kb.setImageProcessingMode("OFF");
    List<Chunk> chunks = List.of(new Chunk(0, "text", 4));
    List<float[]> vectors = List.of(new float[] {0.1f});
    List<DocumentChunk> inserted = List.of(chunkEntity(700L, 0));
    ExtractedImage image = extractedImage(0);

    when(documentMapper.selectById(9L)).thenReturn(doc);
    when(knowledgeBaseMapper.selectById(10L)).thenReturn(kb);
    when(documentParser.parse(anyString(), anyString()))
        .thenReturn(new ParseResult("text", 1L, 1, List.of("text"), List.of(image)));
    when(chunkingService.split(eq(doc), eq(kb), eq("text"))).thenReturn(chunkingResult(chunks));
    when(embeddingService.embedBatch(List.of("text"))).thenReturn(vectors);
    doReturn(inserted)
        .when(documentPipelineService)
        .insertChunks(eq(9L), eq(10L), eq(chunks), eq(vectors), eq("RESUME"), eq("RECURSIVE"));
    when(esIndexService.indexChunks(inserted, doc)).thenReturn(true);

    documentPipelineService.processDocument(9L);

    verify(imagePipelineSupport, never()).processSingleImage(any(), any(), any(), any(), anyInt(), any());
    verify(imagePipelineService, never()).insertImageChunks(anyList());
    verify(documentPipelineService).updateDocumentChunkCount(9L, 1);
  }

  @Test
  void processDocument_imageProcessingOnProcessesEmbeddedImages() throws Exception {
    Document doc = document(10L, 10L, "mixed.pdf");
    KnowledgeBase kb = knowledgeBase(10L);
    kb.setImageProcessingMode("ON");
    List<Chunk> chunks = List.of(new Chunk(0, "text", 4));
    List<float[]> vectors = List.of(new float[] {0.1f});
    List<DocumentChunk> insertedText = List.of(chunkEntity(800L, 0));
    DocumentChunk imageDesc = chunkEntity(801L, 1);
    imageDesc.setChunkModality("IMAGE_DESC");
    DocumentChunk ocrText = chunkEntity(802L, 2);
    ocrText.setChunkModality("OCR_TEXT");
    List<DocumentChunk> imageChunks = List.of(ocrText, imageDesc);

    when(documentMapper.selectById(10L)).thenReturn(doc);
    when(knowledgeBaseMapper.selectById(10L)).thenReturn(kb);
    when(documentParser.parse(anyString(), anyString()))
        .thenReturn(new ParseResult("text", 1L, 1, List.of("text"), List.of(extractedImage(0))));
    when(chunkingService.split(eq(doc), eq(kb), eq("text"))).thenReturn(chunkingResult(chunks));
    when(embeddingService.embedBatch(List.of("text"))).thenReturn(vectors);
    doReturn(insertedText)
        .when(documentPipelineService)
        .insertChunks(eq(10L), eq(10L), eq(chunks), eq(vectors), eq("RESUME"), eq("RECURSIVE"));
    when(esIndexService.indexChunks(insertedText, doc)).thenReturn(true);
    when(imagePipelineSupport.processSingleImage(any(), any(), eq(doc), any(ImageChunkContext.class), eq(1), anyString()))
        .thenReturn(imageChunks);
    when(imagePipelineService.insertImageChunks(imageChunks)).thenReturn(imageChunks);
    when(esIndexService.indexChunks(imageChunks, doc)).thenReturn(true);

    documentPipelineService.processDocument(10L);

    verify(imagePipelineSupport).processSingleImage(any(), eq("image/png"), eq(doc), any(ImageChunkContext.class), eq(1), anyString());
    verify(imagePipelineService).insertImageChunks(imageChunks);
    verify(documentPipelineService).updateDocumentChunkCount(10L, 3);
  }

  @Test
  void insertChunks_batchesLargeInput() throws Exception {
    List<Chunk> chunks = new ArrayList<>();
    List<float[]> vectors = new ArrayList<>();
    for (int i = 0; i < 55; i++) {
      chunks.add(new Chunk(i, "chunk-" + i, 6));
      vectors.add(new float[] {0.1f});
    }

    AtomicInteger batchIndex = new AtomicInteger();
    doAnswer(
            inv -> {
              int batch = batchIndex.getAndIncrement();
              int start = batch == 0 ? 0 : 50;
              int count = batch == 0 ? 50 : 5;
              PreparedStatementCreator psc = inv.getArgument(0);
              RowCallbackHandler rch = inv.getArgument(1);
              Connection conn = org.mockito.Mockito.mock(Connection.class);
              PreparedStatement ps = org.mockito.Mockito.mock(PreparedStatement.class);
              ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
              when(conn.prepareStatement(anyString())).thenReturn(ps);
              when(ps.executeQuery()).thenReturn(rs);
              AtomicInteger rowNum = new AtomicInteger(0);
              when(rs.next()).thenAnswer(i -> rowNum.getAndIncrement() < count);
              when(rs.getLong("id")).thenAnswer(i -> 1000L + start + rowNum.get() - 1);
              when(rs.getInt("chunk_index")).thenAnswer(i -> start + rowNum.get() - 1);
              psc.createPreparedStatement(conn);
              while (rs.next()) {
                rch.processRow(rs);
              }
              return null;
            })
        .when(jdbcTemplate)
        .query(any(PreparedStatementCreator.class), any(RowCallbackHandler.class));

    List<DocumentChunk> inserted =
        documentPipelineService.insertChunks(1L, 10L, chunks, vectors, "TEXT");

    assertThat(inserted).hasSize(55);
    assertThat(batchIndex.get()).isEqualTo(2);
  }

  @Test
  void insertChunks_missingReturnedRow_throws() throws Exception {
    List<Chunk> chunks = List.of(new Chunk(0, "a", 1), new Chunk(1, "b", 1));
    List<float[]> vectors = List.of(new float[] {0.1f}, new float[] {0.2f});

    doAnswer(
            inv -> {
              PreparedStatementCreator psc = inv.getArgument(0);
              RowCallbackHandler rch = inv.getArgument(1);
              Connection conn = org.mockito.Mockito.mock(Connection.class);
              PreparedStatement ps = org.mockito.Mockito.mock(PreparedStatement.class);
              ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
              when(conn.prepareStatement(anyString())).thenReturn(ps);
              when(ps.executeQuery()).thenReturn(rs);
              when(rs.next()).thenReturn(true, false);
              when(rs.getLong("id")).thenReturn(100L);
              when(rs.getInt("chunk_index")).thenReturn(0);
              psc.createPreparedStatement(conn);
              rch.processRow(rs);
              return null;
            })
        .when(jdbcTemplate)
        .query(any(PreparedStatementCreator.class), any(RowCallbackHandler.class));

    assertThatThrownBy(() -> documentPipelineService.insertChunks(1L, 10L, chunks, vectors, "TEXT"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("缺少返回记录");
  }

  @Test
  void incrementKbCount_noKnowledgeBase_isNoOp() {
    when(knowledgeBaseMapper.selectById(99L)).thenReturn(null);

    documentPipelineService.incrementKbCount(99L, 3, true);

    verify(knowledgeBaseMapper, never()).update(org.mockito.ArgumentMatchers.isNull(), any());
  }

  private static Document document(long id, long kbId, String filename) {
    Document doc = new Document();
    doc.setId(id);
    doc.setKbId(kbId);
    doc.setFilename(filename);
    doc.setFilePath("/data/" + filename);
    doc.setFileType("application/pdf");
    doc.setParseStatus("PENDING");
    doc.setVersion(1);
    doc.setChunkType("RESUME");
    return doc;
  }

  private static KnowledgeBase knowledgeBase(long id) {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(id);
    kb.setChunkSize(500);
    kb.setChunkOverlap(50);
    kb.setDocCount(0);
    kb.setChunkCount(0);
    kb.setImageProcessingMode("OFF");
    return kb;
  }

  private static ExtractedImage extractedImage(int index) {
    byte[] bytes = new byte[9 * 1024];
    bytes[0] = 1;
    return new ExtractedImage(bytes, "image/png", 1, index, "surrounding text", "Figure " + index);
  }

  private static ChunkingResult chunkingResult(List<Chunk> chunks) {
    return new ChunkingResult("RECURSIVE", new ChunkParams(), chunks);
  }

  private static DocumentChunk chunkEntity(long id, int index) {
    DocumentChunk chunk = new DocumentChunk();
    chunk.setId(id);
    chunk.setDocId(1L);
    chunk.setKbId(10L);
    chunk.setChunkIndex(index);
    chunk.setContent("chunk-" + index);
    chunk.setTokenCount(5);
    chunk.setChunkType("RESUME");
    chunk.setCreatedAt(LocalDateTime.now());
    return chunk;
  }
}
