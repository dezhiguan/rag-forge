package com.ragforge.maintenance;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ragforge.config.EvalProperties;
import com.ragforge.mapper.EvalExperimentMapper;
import com.ragforge.model.entity.EvalExperiment;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 兜底恢复卡在 {@code running} 的评测实验。
 *
 * <p>实验的检索+评分在内存 {@code @Async} 任务里执行，状态由该任务落库为 completed/failed。若执行进程
 * 崩溃/被重启（如 2026-07-07 消融并发压垮 api 堆 OOM → liveness 杀 pod），内存任务随之消失，实验行永久
 * 卡在 running，无人终结，且前端会持续轮询。此任务按 created_at 超阈值把残留 running 一律置 failed。
 *
 * <p>阈值取较大值（默认 15min）：正常实验（含串行化的消融批次排队）远早于此完成，只有真正孤立的才会被扫到。
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class StuckEvalExperimentRecoveryJob {

  private final EvalExperimentMapper evalExperimentMapper;
  private final EvalProperties evalProperties;

  @Scheduled(fixedDelayString = "${ragforge.eval.stuck-recovery-interval-ms:300000}")
  @SchedulerLock(
      name = "StuckEvalExperimentRecoveryJob_recoverStuckRunning",
      lockAtMostFor = "PT5M",
      lockAtLeastFor = "PT0S")
  public void recoverStuckRunning() {
    int minutes = Math.max(5, evalProperties.getStuckRunningMinutes());
    LocalDateTime threshold = LocalDateTime.now().minusMinutes(minutes);
    int updated =
        evalExperimentMapper.update(
            null,
            new LambdaUpdateWrapper<EvalExperiment>()
                .eq(EvalExperiment::getStatus, "running")
                .lt(EvalExperiment::getCreatedAt, threshold)
                .set(EvalExperiment::getStatus, "failed"));
    if (updated > 0) {
      log.warn("Stuck eval experiment recovery: marked {} running > {} min as failed", updated, minutes);
    }
  }
}
