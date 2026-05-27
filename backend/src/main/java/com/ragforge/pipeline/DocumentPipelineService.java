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
import com.ragforge.pipeline.chunker.TextChunker;
import com.ragforge.pipeline.embedder.EmbeddingService;
import com.ragforge.pipeline.indexer.EsIndexService;
import com.ragforge.pipeline.parser.DocumentParser;
import com.ragforge.pipeline.parser.ParseResult;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentPipelineService {

  private static final String STATUS_PARSING = "parsing";
  private static final String STATUS_CHUNKING = "chunking";
  private static final String STATUS_EMBEDDING = "embedding";
  private static final String STATUS_INDEXING = "indexing";
  private static final String STATUS_COMPLETED = "completed";
  private static final String STATUS_FAILED = "failed";
  private static final String STATUS_PENDING = "pending";

  private final DocumentMapper documentMapper;
  private final DocumentChunkMapper documentChunkMapper;
  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final DocumentParser documentParser;
  private final TextChunker textChunker;
  private final EmbeddingService embeddingService;
  private final EsIndexService esIndexService;

  @Lazy @Autowired private DocumentPipelineService self;

  /** Main flow — no transaction; each step commits in its own REQUIRES_NEW transaction. */
  public void processDocument(Long documentId) {
    try {
      Document doc = documentMapper.selectById(documentId);
      if (doc == null) {
        throw new BizException(404, "文档不存在: " + documentId);
      }

      KnowledgeBase kb = knowledgeBaseMapper.selectById(doc.getKbId());
      if (kb == null) {
        throw new BizException(404, "知识库不存在: " + doc.getKbId());
      }

      String statusAtStart = doc.getParseStatus();
      boolean incrementDocCount =
          STATUS_PENDING.equals(statusAtStart) && coalesce(doc.getVersion(), 1) == 1;

      self.cleanupArtifacts(documentId);

      int chunkSize = coalesce(kb.getChunkSize(), TextChunker.DEFAULT_CHUNK_SIZE);
      int chunkOverlap = coalesce(kb.getChunkOverlap(), TextChunker.DEFAULT_CHUNK_OVERLAP);

      self.updateStatus(documentId, STATUS_PARSING);

      ParseResult parseResult = documentParser.parse(doc.getFilePath(), doc.getFileType());
      String text = parseResult.getText() == null ? "" : parseResult.getText();

      self.updateStatus(documentId, STATUS_CHUNKING);

      List<Chunk> chunks = textChunker.chunk(text, chunkSize, chunkOverlap);

      self.updateStatus(documentId, STATUS_EMBEDDING);

      List<String> contents = chunks.stream().map(Chunk::getContent).toList();
      List<float[]> vectors =
          contents.isEmpty() ? List.of() : embeddingService.embedBatch(contents);
      if (vectors.size() != contents.size()) {
        throw new BizException("Embedding 数量与分块数量不一致");
      }

      self.updateStatus(documentId, STATUS_INDEXING);

      List<DocumentChunk> documentChunks =
          self.insertChunks(documentId, doc.getKbId(), chunks, vectors);

      esIndexService.indexChunks(documentChunks, doc);

      self.updateStatus(documentId, STATUS_COMPLETED);
      self.updateDocumentChunkCount(documentId, chunks.size());
      self.incrementKbCount(doc.getKbId(), chunks.size(), incrementDocCount);

      log.info(
          "Document pipeline completed: docId={}, chunks={}, incrementDocCount={}",
          documentId,
          chunks.size(),
          incrementDocCount);
    } catch (Exception e) {
      log.error("文档处理失败: docId={}", documentId, e);
      self.updateStatusWithError(documentId, STATUS_FAILED, e.getMessage());
      throw e;
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
            .set(Document::getErrorMsg, null));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void updateStatusWithError(Long docId, String status, String errorMsg) {
    documentMapper.update(
        null,
        new LambdaUpdateWrapper<Document>()
            .eq(Document::getId, docId)
            .set(Document::getParseStatus, status)
            .set(Document::getErrorMsg, truncateError(errorMsg)));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<DocumentChunk> insertChunks(
      Long docId, Long kbId, List<Chunk> chunks, List<float[]> vectors) {
    LocalDateTime now = LocalDateTime.now();
    List<DocumentChunk> inserted = new ArrayList<>(chunks.size());
    for (int i = 0; i < chunks.size(); i++) {
      Chunk chunk = chunks.get(i);
      DocumentChunk entity = new DocumentChunk();
      entity.setDocId(docId);
      entity.setKbId(kbId);
      entity.setChunkIndex(chunk.getIndex());
      entity.setContent(chunk.getContent());
      entity.setContentVector(new PGvector(vectors.get(i)));
      entity.setTokenCount(chunk.getTokenCount());
      entity.setCreatedAt(now);
      documentChunkMapper.insert(entity);
      inserted.add(entity);
    }
    return inserted;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void updateDocumentChunkCount(Long docId, int count) {
    documentMapper.update(
        null,
        new LambdaUpdateWrapper<Document>()
            .eq(Document::getId, docId)
            .set(Document::getChunkCount, count));
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
}
