package com.ragforge.pipeline.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import com.ragforge.common.BizException;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.pipeline.embedder.EmbeddingInput;
import com.ragforge.pipeline.embedder.VlEmbeddingClient;
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
  private final VlEmbeddingClient vlEmbeddingClient;
  private final ObjectMapper objectMapper;

  public List<DocumentChunk> processSingleImage(
      byte[] imageBytes,
      String imageContentType,
      Document doc,
      ImageChunkContext context,
      int startChunkIndex,
      String imageKey) {
    return processImage(imageBytes, imageContentType, doc, context, startChunkIndex);
  }

  public List<DocumentChunk> processStandaloneImage(
      byte[] imageBytes,
      String imageContentType,
      Document doc,
      int startChunkIndex,
      String imageKey) {
    return processImage(imageBytes, imageContentType, doc, new ImageChunkContext(), startChunkIndex);
  }

  private List<DocumentChunk> processImage(
      byte[] imageBytes,
      String imageContentType,
      Document doc,
      ImageChunkContext context,
      int chunkIndex) {
    if (imageBytes == null || imageBytes.length == 0) {
      return List.of();
    }

    String ocrText = "";
    try {
      OcrResult ocr = ocrClient.recognize(imageBytes, imageContentType, doc.getFilename());
      if (ocr != null) {
        ocrText = ocr.getText();
      }
    } catch (Exception e) {
      throw e instanceof RuntimeException runtimeException ? runtimeException : new BizException(e.getMessage());
    }

    String content =
        StringUtils.hasText(ocrText)
            ? contentWithContext(ocrText, context)
            : contentWithContext("[图片：" + doc.getFilename() + "]", context);
    float[] vector =
        vlEmbeddingClient.embed(List.of(EmbeddingInput.image(imageBytes, imageContentType))).get(0);

    DocumentChunk chunk = new DocumentChunk();
    chunk.setDocId(doc.getId());
    chunk.setKbId(doc.getKbId());
    chunk.setChunkIndex(chunkIndex);
    chunk.setContent(content);
    chunk.setTokenCount(content == null ? 0 : Math.max(1, content.length() / 2));
    chunk.setChunkType(ChunkModality.IMAGE);
    chunk.setChunkModality(ChunkModality.IMAGE);
    chunk.setVlVector(new PGvector(vector));
    chunk.setChunkMetadataJson(metadataJson(context));
    return List.of(chunk);
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
