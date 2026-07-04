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

  /**
   * PROCESSING 卡住阈值（分钟）：updated_at 超过该时长仍处于 PROCESSING，视为 worker 处理途中崩溃残留。
   * 必须严格大于消费者 CAS 的 5 分钟重认领窗口，否则重投也不会被重新认领（实际取 max(6, 本值)）。
   */
  @Value("${ragforge.maintenance.stuck-processing-minutes:15}")
  private int stuckProcessingMinutes;

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

  /**
   * 兜底恢复卡在 PROCESSING 的文档。worker 在处理途中崩溃/重启时，未 ack 的消息重投会因 doc 仍 PROCESSING
   * 且 updated_at &lt; 5min 被消费者 CAS 跳过并静默 ack 丢弃；而 {@link #recoverStuckPending()} 只捞 PENDING，
   * 导致此类文档永久卡 PROCESSING（此前只能手工 SQL 救）。此任务按 updated_at 超阈值重新投递，消费者
   * CAS(PROCESSING AND updated_at &lt; now-5min) 会重新认领；阈值取 max(6, 配置值) 确保严格大于 CAS 窗口。
   */
  @Scheduled(fixedDelayString = "${ragforge.maintenance.stuck-processing-recovery-interval-ms:120000}")
  @SchedulerLock(
      name = "StuckDocumentRecoveryJob_recoverStuckProcessing",
      lockAtMostFor = "PT5M",
      lockAtLeastFor = "PT0S")
  public void recoverStuckProcessing() {
    int minutes = Math.max(6, stuckProcessingMinutes);
    LocalDateTime threshold = LocalDateTime.now().minusMinutes(minutes);
    List<Document> stuck =
        documentMapper.selectList(
            new LambdaQueryWrapper<Document>()
                .eq(Document::getParseStatus, "PROCESSING")
                .lt(Document::getUpdatedAt, threshold)
                .orderByAsc(Document::getId)
                .last("LIMIT " + Math.max(1, batchLimit)));
    if (stuck.isEmpty()) {
      return;
    }
    int resent = 0;
    for (Document d : stuck) {
      try {
        // 重投；消费者 CAS 的 5min 陈旧窗口保证只有真卡住的才被重新认领，先清后建幂等安全。
        documentProcessProducer.send(d.getId());
        resent++;
      } catch (RuntimeException e) {
        log.warn("Stuck PROCESSING recovery re-dispatch failed docId={}: {}", d.getId(), e.getMessage());
      }
    }
    log.warn(
        "Stuck PROCESSING recovery: re-dispatched {} document(s) stuck > {} min", resent, minutes);
  }
}
