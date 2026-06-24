package com.ragforge.document.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.model.dto.RechunkRequest;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.pipeline.chunker.ChunkParams;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

public final class RechunkSupport {

  public static final Set<String> VALID_STRATEGIES =
      Set.of("MARKDOWN_HEADING", "FIXED_WINDOW", "RECURSIVE", "SEMANTIC", "TABLE_AWARE");

  public static final int MIN_CHUNK_SIZE = 64;
  public static final int MAX_CHUNK_SIZE = 2048;
  public static final int MAX_OVERLAP = 512;
  public static final int SEMANTIC_MIN_TEXT_LENGTH = 2000;

  private RechunkSupport() {}

  public static String normalizeStrategy(String strategy) {
    return strategy == null ? null : strategy.trim().toUpperCase(Locale.ROOT);
  }

  public static void validateRequest(RechunkRequest req, int cleanedTextLength) {
    if (req == null || !StringUtils.hasText(req.getStrategy())) {
      return;
    }
    String strategy = normalizeStrategy(req.getStrategy());
    if (!VALID_STRATEGIES.contains(strategy)) {
      throw new BizException(400, "INVALID_STRATEGY");
    }
    if ("SEMANTIC".equals(strategy) && cleanedTextLength < SEMANTIC_MIN_TEXT_LENGTH) {
      throw new BizException(400, "SEMANTIC_REQUIRES_LONG_TEXT");
    }
    if (usesFixedParams(strategy)) {
      Integer chunkSize = req.getChunkSize();
      Integer chunkOverlap = req.getChunkOverlap();
      if (chunkSize != null) {
        if (chunkSize < MIN_CHUNK_SIZE || chunkSize > MAX_CHUNK_SIZE) {
          throw new BizException(400, "CHUNK_SIZE_OUT_OF_RANGE");
        }
      }
      if (chunkOverlap != null) {
        if (chunkOverlap < 0 || chunkOverlap > MAX_OVERLAP) {
          throw new BizException(400, "CHUNK_SIZE_OUT_OF_RANGE");
        }
      }
    }
  }

  public static boolean usesFixedParams(String strategy) {
    String normalized = normalizeStrategy(strategy);
    return "FIXED_WINDOW".equals(normalized) || "RECURSIVE".equals(normalized);
  }

  public static ChunkParams toChunkParams(RechunkRequest req, ChunkParams defaults) {
    ChunkParams params = defaults == null ? new ChunkParams() : defaults;
    if (req == null) {
      return params;
    }
    if (req.getChunkSize() != null) {
      params.setChunkSize(req.getChunkSize());
    }
    if (req.getChunkOverlap() != null) {
      params.setOverlap(req.getChunkOverlap());
    }
    return params;
  }

  public static boolean isImageOnlyDocument(Document doc, java.util.List<DocumentChunk> chunks) {
    if (chunks != null && !chunks.isEmpty()) {
      return chunks.stream().allMatch(chunk -> isImageModality(chunk.getChunkModality()));
    }
    return doc != null
        && doc.getFileType() != null
        && doc.getFileType().toLowerCase(Locale.ROOT).startsWith("image/");
  }

  public static String resolveOldStrategy(java.util.List<DocumentChunk> chunks) {
    if (chunks == null || chunks.isEmpty()) {
      return "MARKDOWN_HEADING";
    }
    if (chunks.stream().allMatch(chunk -> isImageModality(chunk.getChunkModality()))) {
      return "IMAGE_PIPELINE";
    }
    return chunks.stream()
        .map(DocumentChunk::getChunkerStrategy)
        .filter(StringUtils::hasText)
        .findFirst()
        .orElse("MARKDOWN_HEADING");
  }

  public static int resolveCleanedTextLength(Document doc, ObjectMapper objectMapper) {
    if (doc == null) {
      return 0;
    }
    if (StringUtils.hasText(doc.getCleanReportJson())) {
      try {
        JsonNode node = objectMapper.readTree(doc.getCleanReportJson());
        if (node.hasNonNull("cleanedLength")) {
          return node.get("cleanedLength").asInt(0);
        }
      } catch (Exception ignored) {
        // fall through
      }
    }
    if (StringUtils.hasText(doc.getIndexedContent())) {
      return doc.getIndexedContent().length();
    }
    return 0;
  }

  private static boolean isImageModality(String modality) {
    return modality != null && modality.toUpperCase(Locale.ROOT).startsWith("IMAGE");
  }
}
