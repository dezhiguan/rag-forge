package com.ragforge.pipeline.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.pipeline.embedder.VlEmbeddingClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImagePipelineSupportTest {

  @Mock private OcrClient ocrClient;
  @Mock private VlEmbeddingClient vlEmbeddingClient;

  private ImagePipelineSupport support;

  @BeforeEach
  void setUp() {
    support = new ImagePipelineSupport(ocrClient, vlEmbeddingClient, new ObjectMapper());
  }

  @Test
  void embeddedImageOcrFailureThrowsSoCallerCanSkipThatImage() {
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
  void standaloneImageProducesSingleImageChunkWithUnifiedVector() {
    when(ocrClient.recognize(any(), any(), any())).thenReturn(new OcrResult("OCR 文本"));
    when(vlEmbeddingClient.embed(any())).thenReturn(List.of(new float[2560]));

    List<DocumentChunk> chunks =
        support.processStandaloneImage(new byte[] {1, 2, 3}, "image/png", doc(), 0, "image/key.png");

    assertThat(chunks).extracting(DocumentChunk::getChunkModality)
        .containsExactly(ChunkModality.IMAGE);
    assertThat(chunks.get(0).getContent()).contains("OCR 文本");
    assertThat(chunks.get(0).getVlVector()).isNotNull();
  }

  private static Document doc() {
    Document doc = new Document();
    doc.setId(1L);
    doc.setKbId(2L);
    doc.setFilename("img.png");
    return doc;
  }
}
