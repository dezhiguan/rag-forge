package com.ragforge.controller;

import com.ragforge.common.BizException;
import com.ragforge.common.Result;
import com.ragforge.judge.GoldenSetReplayJob;
import com.ragforge.judge.vo.ReplayResultVo;
import com.ragforge.mapper.EvalDatasetMapper;
import com.ragforge.model.entity.EvalDataset;
import com.ragforge.security.KbAccessGuard;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import com.ragforge.service.JudgeQueryService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/evaluation/golden-set")
@PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR')")
public class GoldenSetController {

  private final GoldenSetReplayJob replayJob;
  private final JudgeQueryService judgeQueryService;
  private final EvalDatasetMapper evalDatasetMapper;
  private final KbAccessGuard kbAccessGuard;
  private final Executor goldenReplayExecutor;
  private final LockProvider lockProvider;

  public GoldenSetController(
      GoldenSetReplayJob replayJob,
      JudgeQueryService judgeQueryService,
      EvalDatasetMapper evalDatasetMapper,
      KbAccessGuard kbAccessGuard,
      @Qualifier("goldenReplayExecutor") Executor goldenReplayExecutor,
      LockProvider lockProvider) {
    this.replayJob = replayJob;
    this.judgeQueryService = judgeQueryService;
    this.evalDatasetMapper = evalDatasetMapper;
    this.kbAccessGuard = kbAccessGuard;
    this.goldenReplayExecutor = goldenReplayExecutor;
    this.lockProvider = lockProvider;
  }

  @PostMapping("/replay")
  public Result<ReplayResultVo> replayNow(
      @RequestParam(required = false) Long datasetId,
      @RequestParam(defaultValue = "100") int limit) {
    requireReplayPermission(datasetId);
    Optional<SimpleLock> lock =
        lockProvider.lock(
            new LockConfiguration(
                Instant.now(), GoldenSetReplayJob.SCHEDULER_LOCK_NAME, Duration.ofHours(2), Duration.ZERO));
    if (lock.isEmpty()) {
      throw new BizException(409, "REPLAY_ALREADY_RUNNING");
    }
    SimpleLock replayLock = lock.get();

    ReplayResultVo accepted = new ReplayResultVo();
    accepted.setRequested(Math.max(limit, 0));
    accepted.setDatasetId(datasetId);
    accepted.setMessage("queued");
    accepted.setSuccess(0);
    accepted.setFailed(0);
    accepted.setStartedAt(System.currentTimeMillis());

    try {
      CompletableFuture.runAsync(
          () -> {
            try {
              replayJob.replay(datasetId, limit);
            } catch (Exception e) {
              log.error("Manual golden set replay failed: datasetId={}, limit={}", datasetId, limit, e);
            } finally {
              replayLock.unlock();
            }
          },
          goldenReplayExecutor);
    } catch (RuntimeException e) {
      replayLock.unlock();
      throw e;
    }

    return Result.ok(accepted);
  }

  @GetMapping("/enabled-count")
  public Result<Integer> enabledCount() {
    return Result.ok(judgeQueryService.goldenSetEnabledQuestionCount());
  }

  private void requireReplayPermission(Long datasetId) {
    if (datasetId == null) {
      RagAuthContext context = RagAuthContextHolder.get();
      if (context == null || !context.isAdmin()) {
        throw new BizException(403, "DATASET_ID_REQUIRED");
      }
      return;
    }

    EvalDataset dataset = evalDatasetMapper.selectById(datasetId);
    if (dataset == null) {
      throw new BizException(404, "EVAL_DATASET_NOT_FOUND");
    }
    if (dataset.getKbId() == null || !kbAccessGuard.canRead(dataset.getKbId())) {
      throw new BizException(403, "KB_ACCESS_DENIED");
    }
  }
}
