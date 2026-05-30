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

  @Scheduled(fixedDelayString = "${ragforge.maintenance.calibration-interval-ms:1800000}")
  public void calibrateCounters() {
    long start = System.currentTimeMillis();
    int fixedDocs = calibrateDocumentChunkCounts();
    int fixedKbs = calibrateKnowledgeBaseCounts();
    log.info(
        "Data calibration completed: fixedDocs={} fixedKbs={} elapsedMs={}",
        fixedDocs,
        fixedKbs,
        System.currentTimeMillis() - start);
  }

  private int calibrateDocumentChunkCounts() {
    List<Document> docs = documentMapper.selectList(null);
    int fixed = 0;
    for (Document doc : docs) {
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
        fixed++;
      }
    }
    return fixed;
  }

  private int calibrateKnowledgeBaseCounts() {
    List<KnowledgeBase> kbs =
        knowledgeBaseMapper.selectList(
            new LambdaQueryWrapper<KnowledgeBase>().ne(KnowledgeBase::getStatus, STATUS_DELETED));
    int fixed = 0;
    for (KnowledgeBase kb : kbs) {
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
        fixed++;
      }
    }
    return fixed;
  }
}
