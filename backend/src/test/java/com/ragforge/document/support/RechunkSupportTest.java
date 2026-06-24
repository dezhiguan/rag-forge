package com.ragforge.document.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.model.dto.RechunkRequest;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class RechunkSupportTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void validateRejectsUnknownStrategy() {
    RechunkRequest req = new RechunkRequest();
    req.setStrategy("UNKNOWN");

    assertThatThrownBy(() -> RechunkSupport.validateRequest(req, 5000))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getMessage())
        .isEqualTo("INVALID_STRATEGY");
  }

  @Test
  void validateRejectsSemanticWhenTextTooShort() {
    RechunkRequest req = new RechunkRequest();
    req.setStrategy("SEMANTIC");

    assertThatThrownBy(() -> RechunkSupport.validateRequest(req, 1500))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getMessage())
        .isEqualTo("SEMANTIC_REQUIRES_LONG_TEXT");
  }

  @Test
  void validateRejectsChunkSizeOutOfRange() {
    RechunkRequest req = new RechunkRequest();
    req.setStrategy("FIXED_WINDOW");
    req.setChunkSize(3000);

    assertThatThrownBy(() -> RechunkSupport.validateRequest(req, 5000))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getMessage())
        .isEqualTo("CHUNK_SIZE_OUT_OF_RANGE");
  }

  @Test
  void validateRejectsChunkSizeBelowMinimum() {
    RechunkRequest req = new RechunkRequest();
    req.setStrategy("FIXED_WINDOW");
    req.setChunkSize(64);

    assertThatThrownBy(() -> RechunkSupport.validateRequest(req, 5000))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getMessage())
        .isEqualTo("CHUNK_SIZE_OUT_OF_RANGE");
  }

  @Test
  void validateRejectsChunkOverlapOutOfRange() {
    RechunkRequest req = new RechunkRequest();
    req.setStrategy("FIXED_WINDOW");
    req.setChunkSize(512);
    req.setChunkOverlap(600);

    assertThatThrownBy(() -> RechunkSupport.validateRequest(req, 5000))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getMessage())
        .isEqualTo("CHUNK_OVERLAP_OUT_OF_RANGE");
  }

  @Test
  void detectsImageOnlyDocumentFromChunks() {
    Document doc = new Document();
    doc.setFileType("application/pdf");
    DocumentChunk imageChunk = new DocumentChunk();
    imageChunk.setChunkModality("IMAGE");

    assertThat(RechunkSupport.isImageOnlyDocument(doc, List.of(imageChunk))).isTrue();
  }

  @Test
  void resolveCleanedTextLengthFromCleanReport() throws Exception {
    Document doc = new Document();
    doc.setCleanReportJson(objectMapper.writeValueAsString(java.util.Map.of("cleanedLength", 2400)));

    assertThat(RechunkSupport.resolveCleanedTextLength(doc, objectMapper)).isEqualTo(2400);
  }
}
