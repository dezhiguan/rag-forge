package com.ragforge.maintenance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.model.entity.DocumentChunk;
import com.ragforge.pipeline.indexer.EsIndexService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!acceptance")
@RequiredArgsConstructor
public class EsIndexRepairJob {

  private static final String STATUS_COMPLETED = "completed";

  private final DocumentMapper documentMapper;
  private final DocumentChunkMapper documentChunkMapper;
  private final EsIndexService esIndexService;

  @Scheduled(fixedDelayString = "${ragforge.maintenance.es-repair-interval-ms:1800000}")
  @SchedulerLock(
      name = "EsIndexRepairJob_repairMissingIndexes",
      lockAtMostFor = "PT30M",
      lockAtLeastFor = "PT0S")
  public void repairMissingIndexes() {
    long start = System.currentTimeMillis();
    EsRepairReport report = repairAllMissingIndexes();
    log.info(
        "ES index repair completed: checked={} repaired={} skipped={} failed={} items={} elapsedMs={}",
        report.getCheckedDocuments(),
        report.getRepairedDocuments(),
        report.getSkippedDocuments(),
        report.getFailedDocuments(),
        report.getItems().size(),
        System.currentTimeMillis() - start);
  }

  public EsRepairReport repairAllMissingIndexes() {
    long start = System.currentTimeMillis();
    EsRepairReport report = new EsRepairReport();
    List<Document> docs =
        documentMapper.selectList(
            new LambdaQueryWrapper<Document>().eq(Document::getParseStatus, STATUS_COMPLETED));
    for (Document doc : docs) {
      repairDocument(doc, report);
    }
    report.setElapsedMs(System.currentTimeMillis() - start);
    return report;
  }

  public EsRepairReport repairDocument(Long docId) {
    EsRepairReport report = new EsRepairReport();
    long start = System.currentTimeMillis();
    Document doc = documentMapper.selectById(docId);
    if (doc == null) {
      report.setSkippedDocuments(1);
      report.addItem(docId, null, 0, -1, "SKIPPED", "document not found");
    } else {
      repairDocument(doc, report);
    }
    report.setElapsedMs(System.currentTimeMillis() - start);
    return report;
  }

  private void repairDocument(Document doc, EsRepairReport report) {
    report.setCheckedDocuments(report.getCheckedDocuments() + 1);
    if (!STATUS_COMPLETED.equals(doc.getParseStatus())) {
      report.setSkippedDocuments(report.getSkippedDocuments() + 1);
      report.addItem(
          doc.getId(),
          doc.getFilename(),
          doc.getChunkCount() == null ? 0 : doc.getChunkCount(),
          -1,
          "SKIPPED",
          "document status is " + doc.getParseStatus());
      return;
    }

    long esCount = esIndexService.countByDocId(doc.getId());
    int pgCount = doc.getChunkCount() == null ? 0 : doc.getChunkCount();
    if (esCount < 0) {
      report.setSkippedDocuments(report.getSkippedDocuments() + 1);
      report.addItem(doc.getId(), doc.getFilename(), pgCount, esCount, "SKIPPED", "ES count failed");
      return;
    }
    if (esCount == pgCount) {
      return;
    }

    List<DocumentChunk> chunks =
        documentChunkMapper.selectList(
            new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocId, doc.getId())
                .orderByAsc(DocumentChunk::getChunkIndex));
    boolean ok = esIndexService.indexChunks(chunks, doc);
    if (ok) {
      report.setRepairedDocuments(report.getRepairedDocuments() + 1);
      report.addItem(
          doc.getId(),
          doc.getFilename(),
          chunks.size(),
          esCount,
          "REPAIRED",
          "ES index rebuilt from PG chunks");
    } else {
      report.setFailedDocuments(report.getFailedDocuments() + 1);
      report.addItem(
          doc.getId(),
          doc.getFilename(),
          chunks.size(),
          esCount,
          "FAILED",
          "ES bulk index failed");
    }
    log.warn(
        "ES index repair attempted: docId={} pgChunks={} esChunksBefore={} success={}",
        doc.getId(),
        chunks.size(),
        esCount,
        ok);
  }
}
