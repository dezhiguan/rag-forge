package com.ragforge.maintenance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.model.entity.KnowledgeBase;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataCalibrationJob {

  private static final String STATUS_COMPLETED = "completed";
  private static final String STATUS_DELETED = "deleted";

  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final DocumentMapper documentMapper;
  private final DocumentChunkMapper documentChunkMapper;
  private final JdbcTemplate jdbcTemplate;

  @Scheduled(fixedDelayString = "${ragforge.maintenance.calibration-interval-ms:1800000}")
  public void calibrateCounters() {
    long start = System.currentTimeMillis();
    DataCalibrationReport report = calibrate();
    log.info(
        "Data calibration completed: checkedDocs={} fixedDocs={} missingVectorDocs={} statusMismatchDocs={} checkedKbs={} fixedKbs={} issues={} elapsedMs={}",
        report.getCheckedDocuments(),
        report.getFixedDocuments(),
        report.getDocumentsMissingVector(),
        report.getDocumentsStatusMismatch(),
        report.getCheckedKnowledgeBases(),
        report.getFixedKnowledgeBases(),
        report.getIssues().size(),
        System.currentTimeMillis() - start);
  }

  public DataCalibrationReport calibrate() {
    long start = System.currentTimeMillis();
    DataCalibrationReport report = new DataCalibrationReport();
    calibrateDocumentChunkCounts(report);
    calibrateKnowledgeBaseCounts(report);
    report.setElapsedMs(System.currentTimeMillis() - start);
    return report;
  }

  private void calibrateDocumentChunkCounts(DataCalibrationReport report) {
    List<Document> docs = documentMapper.selectList(null);
    for (Document doc : docs) {
      report.setCheckedDocuments(report.getCheckedDocuments() + 1);
      Long actual =
          documentChunkMapper.selectCount(
              new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocId, doc.getId()));
      int actualCount = actual == null ? 0 : actual.intValue();
      if (doc.getChunkCount() == null || doc.getChunkCount() != actualCount) {
        documentMapper.update(
            null,
            new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, doc.getId())
                .set(Document::getChunkCount, actualCount));
        report.setFixedDocuments(report.getFixedDocuments() + 1);
        report.addIssue(
            "DOCUMENT_CHUNK_COUNT_FIXED",
            doc.getKbId(),
            doc.getId(),
            "document.chunk_count="
                + doc.getChunkCount()
                + " corrected to actual chunk count "
                + actualCount);
      }
      if (STATUS_COMPLETED.equals(doc.getParseStatus()) && actualCount == 0) {
        report.setDocumentsStatusMismatch(report.getDocumentsStatusMismatch() + 1);
        report.addIssue(
            "DOCUMENT_STATUS_MISMATCH",
            doc.getKbId(),
            doc.getId(),
            "document is completed but has no chunks");
      }
      if (!STATUS_COMPLETED.equals(doc.getParseStatus()) && actualCount > 0) {
        report.setDocumentsStatusMismatch(report.getDocumentsStatusMismatch() + 1);
        report.addIssue(
            "DOCUMENT_STATUS_MISMATCH",
            doc.getKbId(),
            doc.getId(),
            "document status is " + doc.getParseStatus() + " but has " + actualCount + " chunks");
      }
      long missingVectorCount = countMissingVectors(doc.getId());
      if (missingVectorCount > 0) {
        report.setDocumentsMissingVector(report.getDocumentsMissingVector() + 1);
        report.addIssue(
            "CHUNK_VECTOR_MISSING",
            doc.getKbId(),
            doc.getId(),
            missingVectorCount + " chunks have null content_vector");
      }
    }
  }

  private void calibrateKnowledgeBaseCounts(DataCalibrationReport report) {
    List<KnowledgeBase> kbs =
        knowledgeBaseMapper.selectList(
            new LambdaQueryWrapper<KnowledgeBase>().ne(KnowledgeBase::getStatus, STATUS_DELETED));
    for (KnowledgeBase kb : kbs) {
      report.setCheckedKnowledgeBases(report.getCheckedKnowledgeBases() + 1);
      Long docCount =
          documentMapper.selectCount(
              new LambdaQueryWrapper<Document>()
                  .eq(Document::getKbId, kb.getId())
                  .eq(Document::getParseStatus, STATUS_COMPLETED));
      Long chunkCount =
          documentChunkMapper.selectCount(
              new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getKbId, kb.getId()));
      int actualDocCount = docCount == null ? 0 : docCount.intValue();
      int actualChunkCount = chunkCount == null ? 0 : chunkCount.intValue();
      if (!Integer.valueOf(actualDocCount).equals(kb.getDocCount())
          || !Integer.valueOf(actualChunkCount).equals(kb.getChunkCount())) {
        knowledgeBaseMapper.update(
            null,
            new LambdaUpdateWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kb.getId())
                .set(KnowledgeBase::getDocCount, actualDocCount)
                .set(KnowledgeBase::getChunkCount, actualChunkCount)
                .set(KnowledgeBase::getUpdatedAt, LocalDateTime.now()));
        report.setFixedKnowledgeBases(report.getFixedKnowledgeBases() + 1);
        report.addIssue(
            "KB_COUNTER_FIXED",
            kb.getId(),
            null,
            "kb counters corrected: docCount "
                + kb.getDocCount()
                + " -> "
                + actualDocCount
                + ", chunkCount "
                + kb.getChunkCount()
                + " -> "
                + actualChunkCount);
      }
    }
  }

  private long countMissingVectors(Long docId) {
    Long count =
        jdbcTemplate.queryForObject(
            "select count(*) from document_chunks where doc_id = ? and content_vector is null",
            Long.class,
            docId);
    return count == null ? 0 : count;
  }
}
