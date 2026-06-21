package com.ragforge.pipeline.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImagePipelineSupportTest {

  @Mock private OcrClient ocrClient;
  @Mock private VisionCaptionClient visionCaptionClient;
  @Mock private ImageEmbeddingClient imageEmbeddingClient;

  private ImagePipelineSupport support;

  @BeforeEach
  void setUp() {
    support = new ImagePipelineSupport(ocrClient, visionCaptionClient, imageEmbeddingClient, new ObjectMapper());
  }

  @Test
  void embeddedImageOcrFailureThrowsSoCallerCanSkipThatImage() {
    when(imageEmbeddingClient.embedImage(any(), any())).thenReturn(new float[1024]);
    when(ocrClient.recognize(any(), any(), any())).thenThrow(new RuntimeException("ocr down"));

    assertThatThrownBy(
            () ->
                support.processSingleImage(
                    new byte[] {1, 2, 3},
                    "image/png",
                    doc(),
                    new ImageChunkContext(2, 1, "ctx", "cap"),
                    10,
                    "k"))
        .hasMessageContaining("ocr down");
  }

  @Test
  void standaloneImageKeepsNoOcrChunkWhenOcrFails() {
    when(imageEmbeddingClient.embedImage(any(), any())).thenReturn(new float[1024]);
    when(ocrClient.recognize(any(), any(), any())).thenThrow(new RuntimeException("ocr down"));
    when(visionCaptionClient.describe(any(), any(), any())).thenReturn("图像描述");

    List<DocumentChunk> chunks =
        support.processStandaloneImage(new byte[] {1, 2, 3}, "image/png", doc(), 0, "image/key.png");

    assertThat(chunks).extracting(DocumentChunk::getChunkModality)
        .containsExactly(ChunkModality.IMAGE_NO_OCR, ChunkModality.IMAGE_DESC);
  }

  private static Document doc() {
    Document doc = new Document();
    doc.setId(1L);
    doc.setKbId(2L);
    doc.setFilename("img.png");
    return doc;
  }
}
