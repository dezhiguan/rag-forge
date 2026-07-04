package com.ragforge.pipeline.image;

import com.ragforge.common.BizException;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.pipeline.indexer.EsIndexService;
import com.ragforge.storage.ObjectStorage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImagePipelineService {

  private static final String STATUS_COMPLETED = "COMPLETED";
  private static final String STATUS_FAILED = "FAILED";
  private static final String STATUS_PROCESSING = "PROCESSING";

  private final DocumentMapper documentMapper;
  private final ObjectStorage objectStorage;
  private final ImagePipelineSupport imagePipelineSupport;
  private final EsIndexService esIndexService;
  private final JdbcTemplate jdbcTemplate;
  private final MultimodalProperties multimodalProperties;
  private final com.ragforge.search.QdrantVectorStore qdrantVectorStore;

  public void processImageDocument(Long documentId) {
    long start = System.currentTimeMillis();
    try {
      Document doc = documentMapper.selectById(documentId);
      if (doc == null) {
        throw new BizException(404, "文档不存在: " + documentId);
      }
      updateStatus(documentId, STATUS_PROCESSING, null);
      cleanup(documentId);
      if (!multimodalProperties.isEnabled()) {
        updateChunkCount(documentId, 0);
        updateStatus(documentId, STATUS_COMPLETED, null);
        log.info(
            "Image pipeline skipped because multimodal is disabled: docId={} latencyMs={}",
            documentId,
            System.currentTimeMillis() - start);
        return;
      }

      byte[] imageBytes = loadImage(doc);
      List<DocumentChunk> chunks =
          imagePipelineSupport.processStandaloneImage(imageBytes, doc.getFileType(), doc, 0, doc.getStorageKey());

      List<DocumentChunk> inserted = insertImageChunks(chunks);
      esIndexService.indexChunks(inserted, doc);
      updateChunkCount(documentId, inserted.size());
      updateStatus(documentId, STATUS_COMPLETED, null);
      log.info(
          "Image pipeline completed: docId={} chunks={} latencyMs={}",
          documentId,
          inserted.size(),
          System.currentTimeMillis() - start);
    } catch (Exception e) {
      log.error("Image pipeline failed: docId={}", documentId, e);
      updateStatus(documentId, STATUS_FAILED, e.getMessage());
      if (e instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new BizException("图片处理失败: " + e.getMessage());
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void cleanup(Long documentId) {
    jdbcTemplate.update("DELETE FROM document_chunks WHERE doc_id = ?", documentId);
    esIndexService.deleteByDocId(documentId);
    qdrantVectorStore.deleteByDocId(documentId);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<DocumentChunk> insertImageChunks(List<DocumentChunk> chunks) {
    if (chunks == null || chunks.isEmpty()) {
      return List.of();
    }
    // 向量改由 Qdrant 承载，PG 不再写 vl_vector 列。
    StringBuilder sql =
        new StringBuilder(
            """
            INSERT INTO document_chunks (
              doc_id, kb_id, chunk_index, content,
              token_count, chunk_type, chunk_modality, chunk_metadata_json, image_key, created_at
            )
            VALUES
            """);
    for (int i = 0; i < chunks.size(); i++) {
      if (i > 0) {
        sql.append(", ");
      }
      sql.append("(?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)");
    }
    sql.append(" RETURNING id, chunk_index");

    LocalDateTime now = LocalDateTime.now();
    jdbcTemplate.query(
        connection -> {
          var ps = connection.prepareStatement(sql.toString());
          int idx = 1;
          for (DocumentChunk chunk : chunks) {
            ps.setLong(idx++, chunk.getDocId());
            ps.setLong(idx++, chunk.getKbId());
            ps.setInt(idx++, chunk.getChunkIndex());
            ps.setString(idx++, chunk.getContent());
            ps.setInt(idx++, chunk.getTokenCount());
            ps.setString(idx++, chunk.getChunkType());
            ps.setString(idx++, chunk.getChunkModality());
            ps.setString(idx++, chunk.getChunkMetadataJson());
            ps.setString(idx++, chunk.getImageKey());
            ps.setObject(idx++, now);
          }
          return ps;
        },
        rs -> {
          int chunkIndex = rs.getInt("chunk_index");
          for (DocumentChunk chunk : chunks) {
            if (chunk.getChunkIndex() != null && chunk.getChunkIndex() == chunkIndex) {
              chunk.setId(rs.getLong("id"));
              chunk.setCreatedAt(now);
              break;
            }
          }
        });

    // 图片 chunk 向量写入 Qdrant（PG 不再存向量）。失败抛异常回滚本事务，保持两侧一致。
    List<com.ragforge.search.QdrantVectorStore.ChunkPoint> points = new ArrayList<>(chunks.size());
    for (DocumentChunk chunk : chunks) {
      if (chunk.getId() == null || chunk.getVlVector() == null) {
        continue;
      }
      points.add(
          new com.ragforge.search.QdrantVectorStore.ChunkPoint(
              chunk.getId(),
              chunk.getKbId(),
              chunk.getDocId(),
              chunk.getChunkType() == null ? "" : chunk.getChunkType(),
              chunk.getVlVector().toArray()));
    }
    qdrantVectorStore.upsert(points);
    return chunks;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void updateStatus(Long documentId, String status, String errorMsg) {
    jdbcTemplate.update(
        """
        UPDATE documents
        SET parse_status = ?, updated_at = NOW(), error_msg = ?
        WHERE id = ?
        """,
        status,
        truncate(errorMsg),
        documentId);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void updateChunkCount(Long documentId, int count) {
    jdbcTemplate.update(
        "UPDATE documents SET chunk_count = ?, updated_at = NOW() WHERE id = ?",
        count,
        documentId);
  }

  private byte[] loadImage(Document doc) throws Exception {
    if (StringUtils.hasText(doc.getStorageBucket())) {
      try (InputStream in = objectStorage.get(doc.getStorageBucket(), doc.getStorageKey())) {
        return in.readAllBytes();
      } catch (RuntimeException e) {
        // 带上 docId + ingestSource 才能在多份遗留 doc 共存时定位是哪一条出问题：
        // 例如旧的 relay 路径（无 kb_ 前缀的 key）跟新 presign 路径（uplt_*）会同时存在，
        // 失败时必须看清是哪条历史 doc 卡住了 worker。
        log.error(
            "Image pipeline OSS get failed: docId={} kbId={} bucket={} key={} ingestSource={} keyShape={}",
            doc.getId(),
            doc.getKbId(),
            doc.getStorageBucket(),
            doc.getStorageKey(),
            doc.getIngestSource(),
            classifyKeyShape(doc.getStorageKey()),
            e);
        throw e;
      }
    }
    return Files.readAllBytes(Path.of(doc.getFilePath()));
  }

  private static String classifyKeyShape(String key) {
    if (key == null) return "null";
    if (key.contains("/kb_") && key.contains("/uplt_")) return "presign-v8";
    if (key.matches(".+/\\d+/[0-9a-fA-F-]{36}/.+")) return "relay-legacy";
    return "other";
  }

  private static String truncate(String message) {
    if (!StringUtils.hasText(message)) {
      return null;
    }
    return message.length() > 2000 ? message.substring(0, 2000) : message;
  }
}
