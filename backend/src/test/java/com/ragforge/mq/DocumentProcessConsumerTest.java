package com.ragforge.mq;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.metrics.RagforgeMetrics;
import com.ragforge.model.entity.Document;
import com.ragforge.pipeline.DocumentPipelineService;
import com.ragforge.pipeline.image.ImagePipelineService;
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

  private DocumentProcessConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer =
        new DocumentProcessConsumer(
            documentMapper,
            pipelineService,
            imagePipelineService,
            new RagforgeMetrics(new SimpleMeterRegistry()),
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
  void onMessageSkipsWhenCasDoesNotClaimDocument() {
    when(documentMapper.markProcessingIfRunnable(10L)).thenReturn(0);

    consumer.onMessage(10L);

    verify(pipelineService, never()).processDocument(10L);
    verify(imagePipelineService, never()).processImageDocument(10L);
  }
}
