package com.ragforge.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.metrics.RagforgeMetrics;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.pipeline.DocumentPipelineService;
import com.ragforge.pipeline.image.ImagePipelineService;
import com.ragforge.security.OrgContextHolder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentProcessConsumerTest {

  @Mock private DocumentMapper documentMapper;
  @Mock private DocumentPipelineService pipelineService;
  @Mock private ImagePipelineService imagePipelineService;
  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;

  private MeterRegistry meterRegistry;
  private DocumentProcessConsumer consumer;

  @BeforeEach
  void setUp() {
    OrgContextHolder.clear();
    meterRegistry = new SimpleMeterRegistry();
    consumer =
        new DocumentProcessConsumer(
            documentMapper,
            pipelineService,
            imagePipelineService,
            new RagforgeMetrics(meterRegistry),
            knowledgeBaseMapper);
  }

  @Test
  void onMessageProcessesWhenCasClaimsDocument() {
    when(documentMapper.markProcessingIfRunnable(10L)).thenReturn(1);
    Document doc = new Document();
    doc.setFileType("application/pdf");
    when(documentMapper.selectById(10L)).thenReturn(doc);

    consumer.onMessage(10L);

    verify(pipelineService).processDocument(10L);
  }

  @Test
  void onMessageSetsKbOrgContextDuringTextProcessingAndClearsAfterwards() {
    when(documentMapper.markProcessingIfRunnable(10L)).thenReturn(1);
    Document doc = new Document();
    doc.setKbId(16L);
    doc.setFileType(" APPLICATION/PDF ; charset=utf-8 ");
    when(documentMapper.selectById(10L)).thenReturn(doc);
    KnowledgeBase kb = new KnowledgeBase();
    kb.setOrgId(99L);
    when(knowledgeBaseMapper.selectById(16L)).thenReturn(kb);

    consumer.onMessage(10L);

    verify(pipelineService).processDocument(10L);
    assertThat(OrgContextHolder.get()).isNull();
    assertThat(meterRegistry.find("ragforge.worker.processing_duration").tag("modality", "text").timer())
        .isNotNull();
  }

  @Test
  void onMessageRoutesImageToImagePipeline() {
    when(documentMapper.markProcessingIfRunnable(10L)).thenReturn(1);
    Document doc = new Document();
    doc.setFileType("image/png");
    when(documentMapper.selectById(10L)).thenReturn(doc);

    consumer.onMessage(10L);

    verify(imagePipelineService).processImageDocument(10L);
    verify(pipelineService, never()).processDocument(10L);
  }

  @Test
  void onMessageRoutesImageWithParametersToImagePipeline() {
    when(documentMapper.markProcessingIfRunnable(10L)).thenReturn(1);
    Document doc = new Document();
    doc.setFileType(" IMAGE/JPEG ; charset=binary ");
    when(documentMapper.selectById(10L)).thenReturn(doc);

    consumer.onMessage(10L);

    verify(imagePipelineService).processImageDocument(10L);
    verify(pipelineService, never()).processDocument(10L);
    assertThat(meterRegistry.find("ragforge.worker.processing_duration").tag("modality", "image").timer())
        .isNotNull();
  }

  @Test
  void onMessageMissingDocumentFallsBackToTextPipeline() {
    when(documentMapper.markProcessingIfRunnable(10L)).thenReturn(1);
    when(documentMapper.selectById(10L)).thenReturn(null);

    consumer.onMessage(10L);

    verify(pipelineService).processDocument(10L);
    verify(knowledgeBaseMapper, never()).selectById(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void onMessageRecordsFailureAndRethrows() {
    when(documentMapper.markProcessingIfRunnable(10L)).thenReturn(1);
    Document doc = new Document();
    doc.setKbId(16L);
    doc.setFileType("application/pdf");
    when(documentMapper.selectById(10L)).thenReturn(doc);
    KnowledgeBase kb = new KnowledgeBase();
    kb.setOrgId(99L);
    when(knowledgeBaseMapper.selectById(16L)).thenReturn(kb);
    doThrow(new IllegalStateException("parse failed")).when(pipelineService).processDocument(10L);

    assertThatThrownBy(() -> consumer.onMessage(10L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("parse failed");

    assertThat(OrgContextHolder.get()).isNull();
    assertThat(
            meterRegistry
                .find("ragforge.worker.failed")
                .tag("reason", "IllegalStateException")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(meterRegistry.find("ragforge.worker.processing_duration").tag("modality", "text").timer())
        .isNotNull();
  }

  @Test
  void onMessageSkipsWhenCasDoesNotClaimDocument() {
    when(documentMapper.markProcessingIfRunnable(10L)).thenReturn(0);

    consumer.onMessage(10L);

    verify(pipelineService, never()).processDocument(10L);
    verify(imagePipelineService, never()).processImageDocument(10L);
  }
}
