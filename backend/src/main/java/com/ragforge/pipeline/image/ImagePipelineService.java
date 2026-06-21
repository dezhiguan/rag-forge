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
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<DocumentChunk> insertImageChunks(List<DocumentChunk> chunks) {
    if (chunks == null || chunks.isEmpty()) {
      return List.of();
    }
    StringBuilder sql =
        new StringBuilder(
            """
            INSERT INTO document_chunks (
              doc_id, kb_id, chunk_index, content, content_vector, image_vector,
              token_count, chunk_type, chunk_modality, image_key, chunk_metadata_json, created_at
            )
            VALUES
            """);
    for (int i = 0; i < chunks.size(); i++) {
      if (i > 0) {
        sql.append(", ");
      }
      sql.append("(?, ?, ?, ?, NULL, ?::vector, ?, ?, ?, ?, ?::jsonb, ?)");
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
            ps.setObject(idx++, chunk.getImageVector());
            ps.setInt(idx++, chunk.getTokenCount());
            ps.setString(idx++, chunk.getChunkType());
            ps.setString(idx++, chunk.getChunkModality());
            ps.setString(idx++, chunk.getImageKey());
            ps.setString(idx++, chunk.getChunkMetadataJson());
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
      }
    }
    return Files.readAllBytes(Path.of(doc.getFilePath()));
  }

  private static String truncate(String message) {
    if (!StringUtils.hasText(message)) {
      return null;
    }
    return message.length() > 2000 ? message.substring(0, 2000) : message;
  }
}
