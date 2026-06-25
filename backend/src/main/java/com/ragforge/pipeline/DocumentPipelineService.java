package com.ragforge.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pgvector.PGvector;
import com.ragforge.common.BizException;
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
import com.ragforge.pipeline.image.MultimodalProperties;
import com.ragforge.pipeline.indexer.EsIndexService;
import com.ragforge.pipeline.parser.DocumentParser;
import com.ragforge.pipeline.parser.ParseResult;
import com.ragforge.pipeline.cleaner.CleanProfile;
import com.ragforge.pipeline.cleaner.CleanProfileService;
import com.ragforge.pipeline.cleaner.CleanResult;
import com.ragforge.pipeline.cleaner.CleaningPipeline;
import com.ragforge.pipeline.cleaner.RawText;
import com.ragforge.pipeline.cleaner.ResolvedCleanProfile;
import com.ragforge.storage.ObjectStorage;
import com.ragforge.storage.ObjectMeta;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentPipelineService {

  private static final String STATUS_PARSING = "PROCESSING";
  private static final String STATUS_CHUNKING = "PROCESSING";
  private static final String STATUS_EMBEDDING = "PROCESSING";
  private static final String STATUS_INDEXING = "PROCESSING";
  private static final String STATUS_COMPLETED = "COMPLETED";
  private static final String STATUS_FAILED = "FAILED";
  private static final String STATUS_PENDING = "PENDING";

  private final DocumentMapper documentMapper;
  private final DocumentChunkMapper documentChunkMapper;
  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final DocumentParser documentParser;
  private final ChunkingService chunkingService;
  private final EmbeddingService embeddingService;
  private final EsIndexService esIndexService;
  private final JdbcTemplate jdbcTemplate;
  private final ObjectStorage objectStorage;
  private final CleaningPipeline cleaningPipeline;
  private final CleanProfileService cleanProfileService;
  private final ObjectMapper objectMapper;
  private final ImagePipelineSupport imagePipelineSupport;
  private final ImagePipelineService imagePipelineService;
  private final List<EmbeddedImageExtractor> embeddedImageExtractors;
  private final MultimodalProperties multimodalProperties;

  @Lazy @Autowired private DocumentPipelineService self;

  /** Main flow — no transaction; each step commits in its own REQUIRES_NEW transaction. */
  public void processDocument(Long documentId) {
    long totalStart = System.currentTimeMillis();
    long loadLatencyMs = 0;
    long cleanupLatencyMs = 0;
    long parseLatencyMs = 0;
    long chunkLatencyMs = 0;
    long embeddingLatencyMs = 0;
    long pgInsertLatencyMs = 0;
    long esIndexLatencyMs = 0;
    long finalizeLatencyMs = 0;
    Long kbId = null;
    String fileType = null;
    int textLength = 0;
    int chunkCount = 0;
    try {
      long stageStart = System.currentTimeMillis();
      Document doc = documentMapper.selectById(documentId);
      if (doc == null) {
        throw new BizException(404, "文档不存在: " + documentId);
      }
      kbId = doc.getKbId();
      fileType = doc.getFileType();

      KnowledgeBase kb = knowledgeBaseMapper.selectById(doc.getKbId());
      if (kb == null) {
        throw new BizException(404, "知识库不存在: " + doc.getKbId());
      }
      loadLatencyMs = System.currentTimeMillis() - stageStart;

      String statusAtStart = doc.getParseStatus();
      boolean incrementDocCount =
          STATUS_PENDING.equals(statusAtStart) && coalesce(doc.getVersion(), 1) == 1;

      stageStart = System.currentTimeMillis();
      self.cleanupArtifacts(documentId);
      cleanupLatencyMs = System.currentTimeMillis() - stageStart;

      self.updateStatus(documentId, STATUS_PARSING);

      stageStart = System.currentTimeMillis();
      ParseResult parseResult = resolveText(doc);
      String text = parseResult.getText() == null ? "" : parseResult.getText();
      textLength = text.length();
      parseLatencyMs = System.currentTimeMillis() - stageStart;

      ResolvedCleanProfile resolvedProfile = cleanProfileService.resolveForKb(doc.getKbId());
      CleanProfile cleanProfile = resolvedProfile.getProfile();
      CleanResult cleanResult =
          cleaningPipeline.clean(
              new RawText(text, doc.getFileType(), parseResult.getPageCount(), parseResult.getPageBoundaries()),
              cleanProfile);
      self.updateCleanReport(
          documentId,
          resolvedProfile.getProfileId(),
          buildCleanReport(text, cleanResult, cleanProfile));
      text = cleanResult.getCleanedText() == null ? "" : cleanResult.getCleanedText();

      self.updateStatus(documentId, STATUS_CHUNKING);

      stageStart = System.currentTimeMillis();
      ChunkingResult chunking = resolveChunking(doc, kb, text);
      List<Chunk> chunks = chunking.getChunks();
      chunkCount = chunks.size();
      chunkLatencyMs = System.currentTimeMillis() - stageStart;

      self.updateStatus(documentId, STATUS_EMBEDDING);

      List<String> contents = chunks.stream().map(Chunk::getContent).toList();
      stageStart = System.currentTimeMillis();
      List<float[]> vectors =
          contents.isEmpty() ? List.of() : embeddingService.embedBatch(contents);
      embeddingLatencyMs = System.currentTimeMillis() - stageStart;
      if (vectors.size() != contents.size()) {
        throw new BizException("Embedding 数量与分块数量不一致");
      }

      self.updateStatus(documentId, STATUS_INDEXING);

      stageStart = System.currentTimeMillis();
      List<DocumentChunk> documentChunks =
          self.insertChunks(
              documentId,
              doc.getKbId(),
              chunks,
              vectors,
              doc.getChunkType(),
              chunking.getStrategy());
      pgInsertLatencyMs = System.currentTimeMillis() - stageStart;

      stageStart = System.currentTimeMillis();
      boolean esIndexed = esIndexService.indexChunks(documentChunks, doc);
      esIndexLatencyMs = System.currentTimeMillis() - stageStart;
      if (!esIndexed) {
        throw new BizException("ES 索引写入失败，等待重试或补偿");
      }

      List<DocumentChunk> imageChunks = processEmbeddedImages(parseResult.getImages(), doc, kb, chunks.size());
      if (!imageChunks.isEmpty()) {
        chunkCount += imageChunks.size();
      }

      stageStart = System.currentTimeMillis();
      self.updateStatus(documentId, STATUS_COMPLETED);
      self.updateDocumentChunkCount(documentId, chunks.size() + imageChunks.size());
      self.incrementKbCount(doc.getKbId(), chunks.size() + imageChunks.size(), incrementDocCount);
      finalizeLatencyMs = System.currentTimeMillis() - stageStart;

      log.info(
          "Document pipeline completed: docId={} kbId={} fileType={} textLength={} chunks={} incrementDocCount={} loadLatency={}ms cleanupLatency={}ms parseLatency={}ms chunkLatency={}ms embeddingLatency={}ms pgInsertLatency={}ms esIndexLatency={}ms finalizeLatency={}ms totalLatency={}ms",
          documentId,
          kbId,
          fileType,
          textLength,
          chunkCount,
          incrementDocCount,
          loadLatencyMs,
          cleanupLatencyMs,
          parseLatencyMs,
          chunkLatencyMs,
          embeddingLatencyMs,
          pgInsertLatencyMs,
          esIndexLatencyMs,
          finalizeLatencyMs,
          System.currentTimeMillis() - totalStart);
    } catch (Exception e) {
      log.error(
          "Document pipeline failed: docId={} kbId={} fileType={} textLength={} chunks={} loadLatency={}ms cleanupLatency={}ms parseLatency={}ms chunkLatency={}ms embeddingLatency={}ms pgInsertLatency={}ms esIndexLatency={}ms finalizeLatency={}ms totalLatency={}ms",
          documentId,
          kbId,
          fileType,
          textLength,
          chunkCount,
          loadLatencyMs,
          cleanupLatencyMs,
          parseLatencyMs,
          chunkLatencyMs,
          embeddingLatencyMs,
          pgInsertLatencyMs,
          esIndexLatencyMs,
          finalizeLatencyMs,
          System.currentTimeMillis() - totalStart,
          e);
      self.updateStatusWithError(documentId, STATUS_FAILED, e.getMessage());
      if (e instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new BizException("文档处理失败: " + e.getMessage());
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void cleanupArtifacts(Long documentId) {
    documentChunkMapper.delete(
        new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocId, documentId));
    esIndexService.deleteByDocId(documentId);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void updateStatus(Long docId, String status) {
    documentMapper.update(
        null,
        new LambdaUpdateWrapper<Document>()
            .eq(Document::getId, docId)
            .set(Document::getParseStatus, status)
            .set(Document::getUpdatedAt, LocalDateTime.now())
            .set(Document::getErrorMsg, null));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void updateStatusWithError(Long docId, String status, String errorMsg) {
    documentMapper.update(
        null,
        new LambdaUpdateWrapper<Document>()
            .eq(Document::getId, docId)
            .set(Document::getParseStatus, status)
            .set(Document::getUpdatedAt, LocalDateTime.now())
            .set(Document::getErrorMsg, truncateError(errorMsg)));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<DocumentChunk> insertChunks(
      Long docId, Long kbId, List<Chunk> chunks, List<float[]> vectors, String docChunkType) {
    return insertChunks(docId, kbId, chunks, vectors, docChunkType, "FIXED_WINDOW");
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<DocumentChunk> insertChunks(
      Long docId,
      Long kbId,
      List<Chunk> chunks,
      List<float[]> vectors,
      String docChunkType,
      String chunkerStrategy) {
    LocalDateTime now = LocalDateTime.now();
    List<DocumentChunk> inserted = new ArrayList<>(chunks.size());
    int batchSize = 50;
    for (int offset = 0; offset < chunks.size(); offset += batchSize) {
      int end = Math.min(offset + batchSize, chunks.size());
      inserted.addAll(
          insertChunkBatch(docId, kbId, chunks, vectors, offset, end, now, docChunkType, chunkerStrategy));
    }
    return inserted;
  }

  private List<DocumentChunk> insertChunkBatch(
      Long docId,
      Long kbId,
      List<Chunk> chunks,
      List<float[]> vectors,
      int start,
      int end,
      LocalDateTime now,
      String docChunkType,
      String chunkerStrategy) {
    StringBuilder sql =
        new StringBuilder(
            """
            INSERT INTO document_chunks (
              doc_id, kb_id, chunk_index, content, vl_vector, token_count, chunk_type,
              chunker_strategy, chunker_params_json, heading_path, created_at
            )
            VALUES
            """);
    for (int i = start; i < end; i++) {
      if (i > start) {
        sql.append(", ");
      }
      sql.append("(?, ?, ?, ?, ?::vector, ?, ?, ?, ?::jsonb, ?, ?)");
    }
    sql.append(" RETURNING id, chunk_index");

    Map<Integer, DocumentChunk> chunkByIndex = new HashMap<>();
    jdbcTemplate.query(
        connection -> {
          var ps = connection.prepareStatement(sql.toString());
          int idx = 1;
          for (int i = start; i < end; i++) {
            Chunk chunk = chunks.get(i);
            ps.setLong(idx++, docId);
            ps.setLong(idx++, kbId);
            ps.setInt(idx++, chunk.getIndex());
            ps.setString(idx++, chunk.getContent());
            ps.setObject(idx++, new PGvector(vectors.get(i)));
            ps.setInt(idx++, chunk.getTokenCount());
            ps.setString(idx++, docChunkType);
            ps.setString(idx++, chunkerStrategy);
            ps.setString(idx++, serializeChunkParams(chunk));
            ps.setString(idx++, chunk.getHeadingPath());
            ps.setObject(idx++, now);
          }
          return ps;
        },
        rs -> {
          long id = rs.getLong("id");
          int chunkIndex = rs.getInt("chunk_index");
          DocumentChunk entity = new DocumentChunk();
          entity.setId(id);
          entity.setDocId(docId);
          entity.setKbId(kbId);
          entity.setChunkIndex(chunkIndex);
          chunkByIndex.put(chunkIndex, entity);
        });

    for (int i = start; i < end; i++) {
      Chunk chunk = chunks.get(i);
      DocumentChunk entity = chunkByIndex.get(chunk.getIndex());
      if (entity == null) {
        throw new BizException("批量插入 chunk 失败，缺少返回记录: chunkIndex=" + chunk.getIndex());
      }
      entity.setContent(chunk.getContent());
      entity.setVlVector(new PGvector(vectors.get(i)));
      entity.setTokenCount(chunk.getTokenCount());
      entity.setChunkType(docChunkType);
      entity.setChunkerStrategy(chunkerStrategy);
      entity.setChunkerParamsJson(serializeChunkParams(chunk));
      entity.setHeadingPath(chunk.getHeadingPath());
      entity.setCreatedAt(now);
    }

    List<DocumentChunk> ordered = new ArrayList<>(end - start);
    for (int i = start; i < end; i++) {
      ordered.add(chunkByIndex.get(chunks.get(i).getIndex()));
    }
    return ordered;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void updateDocumentChunkCount(Long docId, int count) {
    documentMapper.update(
        null,
        new LambdaUpdateWrapper<Document>()
            .eq(Document::getId, docId)
            .set(Document::getChunkCount, count)
            .set(Document::getUpdatedAt, LocalDateTime.now()));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void updateCleanReport(Long docId, Long profileId, String reportJson) {
    documentMapper.updateCleanReport(docId, profileId, reportJson);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void incrementKbCount(Long kbId, int chunkCount, boolean incrementDocCount) {
    KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
    if (kb == null) {
      return;
    }
    int newChunkCount = coalesce(kb.getChunkCount(), 0) + chunkCount;
    int newDocCount = coalesce(kb.getDocCount(), 0) + (incrementDocCount ? 1 : 0);
    knowledgeBaseMapper.update(
        null,
        new LambdaUpdateWrapper<KnowledgeBase>()
            .eq(KnowledgeBase::getId, kbId)
            .set(KnowledgeBase::getChunkCount, newChunkCount)
            .set(KnowledgeBase::getDocCount, newDocCount)
            .set(KnowledgeBase::getUpdatedAt, LocalDateTime.now()));
  }

  private static String truncateError(String message) {
    if (!StringUtils.hasText(message)) {
      return "unknown error";
    }
    return message.length() > 2000 ? message.substring(0, 2000) : message;
  }

  private static int coalesce(Integer value, int defaultValue) {
    return value == null ? defaultValue : value;
  }

  private String serializeChunkParams(Chunk chunk) {
    if (chunk == null || chunk.getChunkParamsJson() == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(chunk.getChunkParamsJson());
    } catch (Exception e) {
      return null;
    }
  }

  private String buildCleanReport(String originalText, CleanResult result, CleanProfile profile) {
    Map<String, Object> report = new HashMap<>();
    report.put("originalSample", sample(originalText));
    report.put("cleanedSample", sample(result.getCleanedText()));
    report.put("removedRegions", result.getRemovedRegions());
    report.put("piiHits", result.getPiiHits());
    report.put("llmTokensUsed", result.getLlmTokensUsed());
    report.put("profile", profile);
    report.put("originalLength", originalText == null ? 0 : originalText.length());
    report.put("cleanedLength", result.getCleanedText() == null ? 0 : result.getCleanedText().length());
    try {
      return objectMapper.writeValueAsString(report);
    } catch (Exception e) {
      return "{\"error\":\"clean_report_serialize_failed\"}";
    }
  }

  private static String sample(String text) {
    if (text == null) {
      return "";
    }
    return text.length() > 8000 ? text.substring(0, 8000) : text;
  }

  private ChunkingResult resolveChunking(Document doc, KnowledgeBase kb, String text) {
    if (!StringUtils.hasText(doc.getRechunkStrategy())) {
      return chunkingService.split(doc, kb, text);
    }
    try {
      ChunkParams overrideParams = null;
      if (StringUtils.hasText(doc.getRechunkParamsJson())) {
        try {
          overrideParams = objectMapper.readValue(doc.getRechunkParamsJson(), ChunkParams.class);
        } catch (Exception e) {
          throw new BizException(400, "INVALID_CHUNKER_PROFILE_JSON");
        }
      }
      ChunkingResult result =
          chunkingService.splitWithStrategy(doc, kb, text, doc.getRechunkStrategy(), overrideParams);
      self.clearRechunkRequest(doc.getId());
      return result;
    } catch (RuntimeException e) {
      if (isStrategyInputError(e)) {
        self.clearRechunkRequest(doc.getId());
      }
      throw e;
    }
  }

  private static boolean isStrategyInputError(RuntimeException e) {
    if (!(e instanceof BizException biz)) {
      return false;
    }
    return STRATEGY_INPUT_ERROR_CODES.contains(biz.getMessage());
  }

  private static final Set<String> STRATEGY_INPUT_ERROR_CODES =
      Set.of(
          "INVALID_STRATEGY",
          "SEMANTIC_REQUIRES_LONG_TEXT",
          "CHUNK_SIZE_OUT_OF_RANGE",
          "CHUNK_OVERLAP_OUT_OF_RANGE",
          "INVALID_CHUNKER_PROFILE_JSON");

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void clearRechunkRequest(Long documentId) {
    documentMapper.update(
        null,
        new LambdaUpdateWrapper<Document>()
            .eq(Document::getId, documentId)
            .set(Document::getRechunkStrategy, null)
            .set(Document::getRechunkParamsJson, null)
            .set(Document::getUpdatedAt, LocalDateTime.now()));
  }

  private ParseResult resolveText(Document doc) throws Exception {
    if (StringUtils.hasText(doc.getIndexedContent())) {
      return new ParseResult(doc.getIndexedContent(), 0L, 1);
    }

    String contentType = normalizeContentType(doc.getFileType());
    if (contentType.startsWith("text/")) {
      long start = System.currentTimeMillis();
      String rawText = readRawText(doc);
      return new ParseResult(
          rawText,
          System.currentTimeMillis() - start,
          1,
          List.of(),
          extractEmbeddedImagesFromObject(doc));
    }

    if (isTikaContentType(contentType)) {
      return parseWithTika(doc);
    }

    if (contentType.startsWith("image/")) {
      throw new BizException(415, "IMAGE_CONTENT_NOT_SUPPORTED_UNTIL_T10");
    }

    throw new BizException(415, "UNSUPPORTED_CONTENT_TYPE: " + contentType);
  }

  private ParseResult parseWithTika(Document doc) throws Exception {
    Path objectTempFile = null;
    try {
      String parsePath = doc.getFilePath();
      if (StringUtils.hasText(doc.getStorageBucket())) {
        objectTempFile = Files.createTempFile("ragforge-object-", suffixFor(doc.getFilename()));
        try (InputStream in = objectStorage.get(doc.getStorageBucket(), doc.getStorageKey())) {
          Files.copy(in, objectTempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        parsePath = objectTempFile.toString();
      }
      return documentParser.parse(parsePath, doc.getFileType());
    } finally {
      if (objectTempFile != null) {
        try {
          Files.deleteIfExists(objectTempFile);
        } catch (Exception ignored) {
          // 临时文件清理失败不影响文档处理结果。
        }
      }
    }
  }

  private List<DocumentChunk> processEmbeddedImages(
      List<ExtractedImage> images, Document doc, KnowledgeBase kb, int startChunkIndex) {
    if (!multimodalProperties.isEnabled()
        || images == null
        || images.isEmpty()
        || !"ON".equalsIgnoreCase(kb.getImageProcessingMode())) {
      return List.of();
    }
    long deadline = System.currentTimeMillis() + embeddedImageMaxProcessingMsPerDoc();
    Semaphore concurrency = new Semaphore(embeddedImageMaxConcurrentTasks());
    AtomicInteger nextChunkIndex = new AtomicInteger(startChunkIndex);
    List<CompletableFuture<List<DocumentChunk>>> futures = new ArrayList<>();
    for (ExtractedImage image : images) {
      futures.add(
          CompletableFuture.supplyAsync(
              () -> processOneEmbeddedImage(image, doc, deadline, concurrency, nextChunkIndex)));
    }

    List<DocumentChunk> allImageChunks = new ArrayList<>();
    for (CompletableFuture<List<DocumentChunk>> future : futures) {
      long remainingMs = Math.max(1, deadline - System.currentTimeMillis());
      try {
        allImageChunks.addAll(future.get(remainingMs, TimeUnit.MILLISECONDS));
      } catch (Exception e) {
        log.warn("Embedded image future skipped: docId={} err={}", doc.getId(), e.getMessage());
      }
    }

    if (allImageChunks.isEmpty()) {
      return List.of();
    }
    try {
      List<DocumentChunk> inserted = imagePipelineService.insertImageChunks(allImageChunks);
      esIndexService.indexChunks(inserted, doc);
      return inserted;
    } catch (Exception e) {
      log.warn("Embedded image chunks skipped after processing: docId={} err={}", doc.getId(), e.getMessage());
      return List.of();
    }
  }

  private List<DocumentChunk> processOneEmbeddedImage(
      ExtractedImage image,
      Document doc,
      long deadline,
      Semaphore concurrency,
      AtomicInteger nextChunkIndex) {
    boolean acquired = false;
    try {
      concurrency.acquire();
      acquired = true;
      if (System.currentTimeMillis() > deadline || image == null || image.getBytes() == null) {
        return List.of();
      }
      if (image.getBytes().length < embeddedImageMinBytes()) {
        // 默认门槛 8KB（RAGFORGE_EMBEDDED_IMAGE_MIN_BYTES）会把小图标/小缩略图直接丢掉，
        // 之前丢得太静默，acc-07 验证时根本看不出来"为什么 2 张图只入了 1 张"。
        // 打 WARN 让运维 / 集成方能直接从日志判断要不要把门槛调低。
        log.warn(
            "Embedded image dropped by min-bytes threshold: docId={} fig={} size={} threshold={} contentType={}",
            doc.getId(),
            image.getFigureIndex(),
            image.getBytes().length,
            embeddedImageMinBytes(),
            image.getContentType());
        return List.of();
      }
      String imageKey = embeddedImageKey(doc, image);
      if (StringUtils.hasText(doc.getStorageBucket())) {
        objectStorage.put(
            doc.getStorageBucket(),
            imageKey,
            new ByteArrayInputStream(image.getBytes()),
            new ObjectMeta(image.getContentType(), (long) image.getBytes().length, null, Map.of()));
      }
      return imagePipelineSupport.processSingleImage(
          image.getBytes(),
          image.getContentType(),
          doc,
          ImageChunkContext.of(image),
          nextChunkIndex.getAndIncrement(),
          imageKey);
    } catch (Exception e) {
      log.warn(
          "Embedded image failed (skip): docId={} fig={} err={}",
          doc.getId(),
          image == null ? null : image.getFigureIndex(),
          e.getMessage());
      return List.of();
    } finally {
      if (acquired) {
        concurrency.release();
      }
    }
  }

  private static String embeddedImageKey(Document doc, ExtractedImage image) {
    String base = StringUtils.hasText(doc.getStorageKey()) ? doc.getStorageKey() : "doc-" + doc.getId();
    return "%s/embedded/p%s_fig%s.%s"
        .formatted(
            base,
            image.getPageNo() == null ? 0 : image.getPageNo(),
            image.getFigureIndex() == null ? 0 : image.getFigureIndex(),
            extensionFor(image.getContentType()));
  }

  private static String extensionFor(String contentType) {
    String normalized = normalizeContentType(contentType);
    return switch (normalized) {
      case "image/jpeg" -> "jpg";
      case "image/gif" -> "gif";
      case "image/webp" -> "webp";
      default -> "png";
    };
  }

  private String readRawText(Document doc) throws Exception {
    if (StringUtils.hasText(doc.getStorageBucket())) {
      try (InputStream in = objectStorage.get(doc.getStorageBucket(), doc.getStorageKey())) {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
    }
    return Files.readString(Path.of(doc.getFilePath()), StandardCharsets.UTF_8);
  }

  private List<ExtractedImage> extractEmbeddedImagesFromObject(Document doc) {
    String contentType = normalizeContentType(doc.getFileType());
    EmbeddedImageExtractor extractor =
        embeddedImageExtractors.stream()
            .filter(candidate -> candidate.supports(contentType))
            .findFirst()
            .orElse(null);
    if (extractor == null) {
      return List.of();
    }
    Path temp = null;
    try {
      temp = Files.createTempFile("ragforge-embedded-", suffixFor(doc.getFilename()));
      if (StringUtils.hasText(doc.getStorageBucket())) {
        try (InputStream in = objectStorage.get(doc.getStorageBucket(), doc.getStorageKey())) {
          Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
      } else {
        Files.copy(Path.of(doc.getFilePath()), temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      return extractor.extract(temp, contentType);
    } catch (Exception e) {
      log.warn("Embedded image extraction skipped for text doc: docId={} err={}", doc.getId(), e.getMessage());
      return List.of();
    } finally {
      if (temp != null) {
        try {
          Files.deleteIfExists(temp);
        } catch (Exception ignored) {
          // 临时文件清理失败不影响主链路。
        }
      }
    }
  }

  private static String normalizeContentType(String contentType) {
    if (!StringUtils.hasText(contentType)) {
      return "";
    }
    int semicolon = contentType.indexOf(';');
    String normalized = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
    return normalized.trim().toLowerCase(java.util.Locale.ROOT);
  }

  private static boolean isTikaContentType(String contentType) {
    return contentType.equals("application/pdf")
        || contentType.equals("application/msword")
        || contentType.startsWith("application/vnd.openxmlformats-");
  }

  private static String suffixFor(String filename) {
    if (!StringUtils.hasText(filename)) {
      return ".bin";
    }
    int dot = filename.lastIndexOf('.');
    if (dot < 0 || dot == filename.length() - 1) {
      return ".bin";
    }
    String suffix = filename.substring(dot);
    return suffix.length() > 20 ? ".bin" : suffix;
  }

  private int embeddedImageMinBytes() {
    return Math.max(0, multimodalProperties.getEmbedded().getMinImageBytes());
  }

  private int embeddedImageMaxConcurrentTasks() {
    return Math.max(1, multimodalProperties.getEmbedded().getMaxConcurrentImageTasks());
  }

  private long embeddedImageMaxProcessingMsPerDoc() {
    return Math.max(1L, multimodalProperties.getEmbedded().getMaxProcessingMsPerDoc());
  }
}
