package com.ragforge.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.model.entity.KnowledgeBase;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class DataCalibrationJobTest {

  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private DocumentMapper documentMapper;
  @Mock private DocumentChunkMapper documentChunkMapper;
  @Mock private JdbcTemplate jdbcTemplate;

  @InjectMocks private DataCalibrationJob dataCalibrationJob;

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
    com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
        assistant, KnowledgeBase.class);
  }

  @Test
  void calibrate_fixesDocumentChunkCountAndReportsIssues() {
    Document doc = new Document();
    doc.setId(10L);
    doc.setKbId(1L);
    doc.setChunkCount(0);
    doc.setParseStatus("completed");

    when(documentMapper.selectList(null)).thenReturn(List.of(doc));
    when(documentChunkMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);
    when(jdbcTemplate.queryForObject(
            eq("select count(*) from document_chunks where doc_id = ? and vl_vector is null"),
            eq(Long.class),
            eq(10L)))
        .thenReturn(2L);
    when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

    DataCalibrationReport report = dataCalibrationJob.calibrate();

    assertThat(report.getCheckedDocuments()).isEqualTo(1);
    assertThat(report.getFixedDocuments()).isEqualTo(1);
    assertThat(report.getDocumentsMissingVector()).isEqualTo(1);
    assertThat(report.getDocumentsStatusMismatch()).isZero();
    assertThat(report.getIssues()).hasSize(2);
    assertThat(report.getElapsedMs()).isGreaterThanOrEqualTo(0);
    verify(documentMapper).update(any(), any(LambdaUpdateWrapper.class));
  }

  @Test
  void calibrate_detectsStatusMismatchWhenNotCompletedButHasChunks() {
    Document doc = new Document();
    doc.setId(11L);
    doc.setKbId(2L);
    doc.setChunkCount(2);
    doc.setParseStatus("processing");

    when(documentMapper.selectList(null)).thenReturn(List.of(doc));
    when(documentChunkMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(2L);
    when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class), eq(11L))).thenReturn(0L);
    when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

    DataCalibrationReport report = dataCalibrationJob.calibrate();

    assertThat(report.getDocumentsStatusMismatch()).isEqualTo(1);
    assertThat(report.getFixedDocuments()).isZero();
    assertThat(report.getIssues()).anyMatch(i -> "DOCUMENT_STATUS_MISMATCH".equals(i.getType()));
  }

  @Test
  void calibrate_fixesKnowledgeBaseCounters() {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(5L);
    kb.setStatus("active");
    kb.setDocCount(0);
    kb.setChunkCount(0);

    when(documentMapper.selectList(null)).thenReturn(List.of());
    when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(kb));
    when(documentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(2L);
    when(documentChunkMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(8L);

    DataCalibrationReport report = dataCalibrationJob.calibrate();

    assertThat(report.getCheckedKnowledgeBases()).isEqualTo(1);
    assertThat(report.getFixedKnowledgeBases()).isEqualTo(1);
    assertThat(report.getIssues()).anyMatch(i -> "KB_COUNTER_FIXED".equals(i.getType()));
    verify(knowledgeBaseMapper).update(any(), any(LambdaUpdateWrapper.class));
  }

  @Test
  void calibrateCounters_delegatesToCalibrate() {
    when(documentMapper.selectList(null)).thenReturn(List.of());
    when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

    dataCalibrationJob.calibrateCounters();

    verify(documentMapper).selectList(null);
  }
}
