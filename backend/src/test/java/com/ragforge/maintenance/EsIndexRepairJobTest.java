package com.ragforge.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.pipeline.indexer.EsIndexService;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EsIndexRepairJobTest {

  @Mock private DocumentMapper documentMapper;
  @Mock private DocumentChunkMapper documentChunkMapper;
  @Mock private EsIndexService esIndexService;

  @InjectMocks private EsIndexRepairJob esIndexRepairJob;

  @BeforeAll
  static void initMybatisPlusTableInfo() {
    com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
        new com.baomidou.mybatisplus.core.MybatisConfiguration();
    org.apache.ibatis.builder.MapperBuilderAssistant assistant =
        new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
    com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
        assistant, Document.class);
    com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
        assistant, DocumentChunk.class);
  }

  @Test
  void repairDocument_notFound_skips() {
    when(documentMapper.selectById(99L)).thenReturn(null);

    EsRepairReport report = esIndexRepairJob.repairDocument(99L);

    assertThat(report.getSkippedDocuments()).isEqualTo(1);
    assertThat(report.getItems()).hasSize(1);
    assertThat(report.getItems().get(0).getStatus()).isEqualTo("SKIPPED");
    verify(esIndexService, never()).indexChunks(any(), any());
  }

  @Test
  void repairDocument_skipsNonCompletedStatus() {
    Document doc = completedDoc(1L, 3);
    doc.setParseStatus("failed");

    when(documentMapper.selectById(1L)).thenReturn(doc);

    EsRepairReport report = esIndexRepairJob.repairDocument(1L);

    assertThat(report.getSkippedDocuments()).isEqualTo(1);
    verify(esIndexService, never()).indexChunks(any(), any());
  }

  @Test
  void repairDocument_skipsWhenEsCountMatchesPgCount() {
    Document doc = completedDoc(2L, 4);
    when(documentMapper.selectById(2L)).thenReturn(doc);
    when(esIndexService.countByDocId(2L)).thenReturn(4L);

    EsRepairReport report = esIndexRepairJob.repairDocument(2L);

    assertThat(report.getCheckedDocuments()).isEqualTo(1);
    assertThat(report.getRepairedDocuments()).isZero();
    verify(esIndexService, never()).indexChunks(any(), any());
  }

  @Test
  void repairDocument_rebuildsEsIndexWhenMismatch() {
    Document doc = completedDoc(3L, 2);
    DocumentChunk chunk = new DocumentChunk();
    chunk.setDocId(3L);
    chunk.setChunkIndex(0);

    when(documentMapper.selectById(3L)).thenReturn(doc);
    when(esIndexService.countByDocId(3L)).thenReturn(0L);
    when(documentChunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(chunk));
    when(esIndexService.indexChunks(List.of(chunk), doc)).thenReturn(true);

    EsRepairReport report = esIndexRepairJob.repairDocument(3L);

    assertThat(report.getRepairedDocuments()).isEqualTo(1);
    assertThat(report.getItems()).anyMatch(i -> "REPAIRED".equals(i.getStatus()));
  }

  @Test
  void repairDocument_recordsFailureWhenBulkIndexFails() {
    Document doc = completedDoc(4L, 1);
    DocumentChunk chunk = new DocumentChunk();
    chunk.setDocId(4L);

    when(documentMapper.selectById(4L)).thenReturn(doc);
    when(esIndexService.countByDocId(4L)).thenReturn(0L);
    when(documentChunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(chunk));
    when(esIndexService.indexChunks(List.of(chunk), doc)).thenReturn(false);

    EsRepairReport report = esIndexRepairJob.repairDocument(4L);

    assertThat(report.getFailedDocuments()).isEqualTo(1);
    assertThat(report.getItems()).anyMatch(i -> "FAILED".equals(i.getStatus()));
  }

  @Test
  void repairAllMissingIndexes_iteratesCompletedDocuments() {
    Document doc = completedDoc(5L, 1);
    when(documentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(doc));
    when(esIndexService.countByDocId(5L)).thenReturn(1L);

    EsRepairReport report = esIndexRepairJob.repairAllMissingIndexes();

    assertThat(report.getCheckedDocuments()).isEqualTo(1);
    assertThat(report.getElapsedMs()).isGreaterThanOrEqualTo(0);
  }

  @Test
  void repairMissingIndexes_delegatesToRepairAll() {
    when(documentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

    esIndexRepairJob.repairMissingIndexes();

    verify(documentMapper).selectList(any(LambdaQueryWrapper.class));
  }

  private static Document completedDoc(long id, int chunkCount) {
    Document doc = new Document();
    doc.setId(id);
    doc.setFilename("doc-" + id + ".txt");
    doc.setParseStatus("completed");
    doc.setChunkCount(chunkCount);
    return doc;
  }
}
