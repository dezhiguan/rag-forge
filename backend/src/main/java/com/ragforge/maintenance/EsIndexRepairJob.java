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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EsIndexRepairJob {

  private static final String STATUS_COMPLETED = "completed";

  private final DocumentMapper documentMapper;
  private final DocumentChunkMapper documentChunkMapper;
  private final EsIndexService esIndexService;

  @Scheduled(fixedDelayString = "${ragforge.maintenance.es-repair-interval-ms:1800000}")
  public void repairMissingIndexes() {
    long start = System.currentTimeMillis();
    List<Document> docs =
        documentMapper.selectList(
            new LambdaQueryWrapper<Document>().eq(Document::getParseStatus, STATUS_COMPLETED));
    int checked = 0;
    int repaired = 0;
    int skipped = 0;
    for (Document doc : docs) {
      checked++;
      long esCount = esIndexService.countByDocId(doc.getId());
      if (esCount < 0) {
        skipped++;
        continue;
      }
      int pgCount = doc.getChunkCount() == null ? 0 : doc.getChunkCount();
      if (esCount == pgCount) {
        continue;
      }
      List<DocumentChunk> chunks =
          documentChunkMapper.selectList(
              new LambdaQueryWrapper<DocumentChunk>()
                  .eq(DocumentChunk::getDocId, doc.getId())
                  .orderByAsc(DocumentChunk::getChunkIndex));
      boolean ok = esIndexService.indexChunks(chunks, doc);
      if (ok) {
        repaired++;
      } else {
        skipped++;
      }
      log.warn(
          "ES index repaired: docId={} pgChunks={} esChunksBefore={} success={}",
          doc.getId(),
          chunks.size(),
          esCount,
          ok);
    }
    log.info(
        "ES index repair completed: checked={} repaired={} skipped={} elapsedMs={}",
        checked,
        repaired,
        skipped,
        System.currentTimeMillis() - start);
  }
}
