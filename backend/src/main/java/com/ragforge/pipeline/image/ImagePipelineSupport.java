package com.ragforge.pipeline.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import com.ragforge.common.BizException;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class ImagePipelineSupport {

  private final OcrClient ocrClient;
  private final VisionCaptionClient visionCaptionClient;
  private final ImageEmbeddingClient imageEmbeddingClient;
  private final ObjectMapper objectMapper;

  public List<DocumentChunk> processSingleImage(
      byte[] imageBytes,
      String imageContentType,
      Document doc,
      ImageChunkContext context,
      int startChunkIndex,
      String imageKey) {
    return processSingleImage(
        imageBytes, imageContentType, doc, context, startChunkIndex, imageKey, false);
  }

  public List<DocumentChunk> processStandaloneImage(
      byte[] imageBytes,
      String imageContentType,
      Document doc,
      int startChunkIndex,
      String imageKey) {
    return processSingleImage(
        imageBytes, imageContentType, doc, new ImageChunkContext(), startChunkIndex, imageKey, true);
  }

  private List<DocumentChunk> processSingleImage(
      byte[] imageBytes,
      String imageContentType,
      Document doc,
      ImageChunkContext context,
      int startChunkIndex,
      String imageKey,
      boolean keepNoOcrChunk) {
    if (imageBytes == null || imageBytes.length == 0) {
      return List.of();
    }

    float[] imageVector = imageEmbeddingClient.embedImage(imageBytes, imageContentType);
    List<DocumentChunk> chunks = new ArrayList<>();
    int index = startChunkIndex;

    try {
      OcrResult ocr = ocrClient.recognize(imageBytes, imageContentType, doc.getFilename());
      if (ocr != null && StringUtils.hasText(ocr.getText())) {
        chunks.add(buildChunk(doc, index++, contentWithContext(ocr.getText(), context), ChunkModality.OCR_TEXT, imageVector, imageKey, context));
      } else if (keepNoOcrChunk) {
        chunks.add(buildChunk(doc, index++, "OCR 未识别到文字", ChunkModality.IMAGE_NO_OCR, imageVector, imageKey, context));
      }
    } catch (Exception e) {
      if (!keepNoOcrChunk) {
        throw e instanceof RuntimeException runtimeException ? runtimeException : new BizException(e.getMessage());
      }
      chunks.add(buildChunk(doc, index++, "OCR 失败：" + e.getMessage(), ChunkModality.IMAGE_NO_OCR, imageVector, imageKey, context));
    }

    String description = visionCaptionClient.describe(imageBytes, imageContentType, doc.getFilename());
    if (!StringUtils.hasText(description)) {
      description = "图片文件：" + doc.getFilename();
    }
    chunks.add(buildChunk(doc, index, contentWithContext(description, context), ChunkModality.IMAGE_DESC, imageVector, imageKey, context));
    return chunks;
  }

  private DocumentChunk buildChunk(
      Document doc,
      int index,
      String content,
      String modality,
      float[] imageVector,
      String imageKey,
      ImageChunkContext context) {
    DocumentChunk chunk = new DocumentChunk();
    chunk.setDocId(doc.getId());
    chunk.setKbId(doc.getKbId());
    chunk.setChunkIndex(index);
    chunk.setContent(content);
    chunk.setTokenCount(content == null ? 0 : Math.max(1, content.length() / 2));
    chunk.setChunkType(modality);
    chunk.setChunkModality(modality);
    chunk.setImageKey(imageKey);
    chunk.setImageVector(new PGvector(imageVector));
    chunk.setChunkMetadataJson(metadataJson(context));
    return chunk;
  }

  private static String contentWithContext(String content, ImageChunkContext context) {
    StringBuilder builder = new StringBuilder();
    if (context != null && StringUtils.hasText(context.getCaptionText())) {
      builder.append("Caption: ").append(context.getCaptionText()).append("\n");
    }
    if (context != null && StringUtils.hasText(context.getSurroundingText())) {
      builder.append("Context: ").append(context.getSurroundingText()).append("\n");
    }
    builder.append(content == null ? "" : content);
    return builder.toString();
  }

  private String metadataJson(ImageChunkContext context) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    if (context != null) {
      metadata.put("pageNo", context.getPageNo());
      metadata.put("figureIndex", context.getFigureIndex());
      metadata.put("surroundingText", context.getSurroundingText());
      metadata.put("captionText", context.getCaptionText());
    }
    try {
      return objectMapper.writeValueAsString(metadata);
    } catch (Exception e) {
      return "{}";
    }
  }
}
