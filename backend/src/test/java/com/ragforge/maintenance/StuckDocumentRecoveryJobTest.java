package com.ragforge.maintenance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.mapper.DocumentMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.mq.DocumentProcessProducer;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class StuckDocumentRecoveryJobTest {

  @Mock private DocumentMapper documentMapper;
  @Mock private DocumentProcessProducer producer;

  private StuckDocumentRecoveryJob job;

  @BeforeEach
  void setUp() {
    job = new StuckDocumentRecoveryJob(documentMapper, producer);
    ReflectionTestUtils.setField(job, "stuckMinutes", 3);
    ReflectionTestUtils.setField(job, "stuckProcessingMinutes", 15);
    ReflectionTestUtils.setField(job, "batchLimit", 200);
  }

  private Document doc(long id) {
    Document d = new Document();
    d.setId(id);
    d.setParseStatus("PENDING");
    return d;
  }

  @Test
  void redispatchesStuckPendingDocuments() {
    when(documentMapper.selectList(any())).thenReturn(List.of(doc(1L), doc(2L), doc(3L)));

    job.recoverStuckPending();

    verify(producer).send(1L);
    verify(producer).send(2L);
    verify(producer).send(3L);
    verify(producer, times(3)).send(any());
  }

  @Test
  void noStuckDocuments_doesNothing() {
    when(documentMapper.selectList(any())).thenReturn(List.of());

    job.recoverStuckPending();

    verify(producer, never()).send(any());
  }

  @Test
  void oneFailedRedispatchDoesNotStopOthers() {
    when(documentMapper.selectList(any())).thenReturn(List.of(doc(1L), doc(2L)));
    org.mockito.Mockito.doThrow(new RuntimeException("mq down")).when(producer).send(1L);

    job.recoverStuckPending(); // 不抛异常

    verify(producer).send(1L);
    verify(producer).send(2L); // 第一个失败不影响第二个
  }

  @Test
  void redispatchesStuckProcessingDocuments() {
    when(documentMapper.selectList(any())).thenReturn(List.of(doc(5L), doc(6L)));

    job.recoverStuckProcessing();

    verify(producer).send(5L);
    verify(producer).send(6L);
    verify(producer, times(2)).send(any());
  }

  @Test
  void noStuckProcessing_doesNothing() {
    when(documentMapper.selectList(any())).thenReturn(List.of());

    job.recoverStuckProcessing();

    verify(producer, never()).send(any());
  }

  @Test
  void stuckProcessingRedispatchFailureDoesNotStopOthers() {
    when(documentMapper.selectList(any())).thenReturn(List.of(doc(7L), doc(8L)));
    org.mockito.Mockito.doThrow(new RuntimeException("mq down")).when(producer).send(7L);

    job.recoverStuckProcessing(); // 不抛异常

    verify(producer).send(7L);
    verify(producer).send(8L);
  }
}
