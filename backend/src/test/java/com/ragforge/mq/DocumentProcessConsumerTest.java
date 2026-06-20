package com.ragforge.mq;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.mapper.DocumentMapper;
import com.ragforge.pipeline.DocumentPipelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentProcessConsumerTest {

  @Mock private DocumentMapper documentMapper;
  @Mock private DocumentPipelineService pipelineService;

  private DocumentProcessConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new DocumentProcessConsumer(documentMapper, pipelineService);
  }

  @Test
  void onMessageProcessesWhenCasClaimsDocument() {
    when(documentMapper.markProcessingIfRunnable(10L)).thenReturn(1);

    consumer.onMessage(10L);

    verify(pipelineService).processDocument(10L);
  }

  @Test
  void onMessageSkipsWhenCasDoesNotClaimDocument() {
    when(documentMapper.markProcessingIfRunnable(10L)).thenReturn(0);

    consumer.onMessage(10L);

    verify(pipelineService, never()).processDocument(10L);
  }
}
