package com.ragforge.maintenance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.model.entity.Document;
import com.ragforge.mq.DocumentProcessProducer;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 兜底恢复长时间卡在 PENDING 的文档：重新投递处理消息。
 *
 * <p>正常情况下 {@link DocumentProcessProducer} 已改为事务提交后再发消息，不会再有"消费者早于 documents 行提交可见
 * 而 CAS 跳过"的竞争。此任务作为防御性网兜底任何漏网场景（消息丢失、消费者 CAS 竞争等），并可自动恢复历史卡住的文档。
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class StuckDocumentRecoveryJob {

  private final DocumentMapper documentMapper;
  private final DocumentProcessProducer documentProcessProducer;

  /** 卡住判定阈值（分钟）：创建超过该时长仍处于 PENDING 视为卡住。 */
  @Value("${ragforge.maintenance.stuck-pending-minutes:3}")
  private int stuckMinutes;

  /** 单轮最多恢复文档数，避免一次性打爆队列。 */
  @Value("${ragforge.maintenance.stuck-recovery-batch:200}")
  private int batchLimit;

  @Scheduled(fixedDelayString = "${ragforge.maintenance.stuck-recovery-interval-ms:120000}")
  @SchedulerLock(
      name = "StuckDocumentRecoveryJob_recoverStuckPending",
      lockAtMostFor = "PT5M",
      lockAtLeastFor = "PT0S")
  public void recoverStuckPending() {
    LocalDateTime threshold = LocalDateTime.now().minusMinutes(Math.max(1, stuckMinutes));
    List<Document> stuck =
        documentMapper.selectList(
            new LambdaQueryWrapper<Document>()
                .eq(Document::getParseStatus, "PENDING")
                .lt(Document::getCreatedAt, threshold)
                .orderByAsc(Document::getId)
                .last("LIMIT " + Math.max(1, batchLimit)));
    if (stuck.isEmpty()) {
      return;
    }
    int resent = 0;
    for (Document d : stuck) {
      try {
        // 重投处理消息；消费者的 CAS(markProcessingIfRunnable) 保证只被认领一次，幂等安全。
        documentProcessProducer.send(d.getId());
        resent++;
      } catch (RuntimeException e) {
        log.warn("Stuck PENDING recovery re-dispatch failed docId={}: {}", d.getId(), e.getMessage());
      }
    }
    log.warn(
        "Stuck PENDING recovery: re-dispatched {} document(s) stuck > {} min", resent, stuckMinutes);
  }
}
