package com.ragforge.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.common.BizException;
import com.ragforge.common.Result;
import com.ragforge.judge.GoldenSetReplayJob;
import com.ragforge.judge.vo.ReplayResultVo;
import com.ragforge.mapper.EvalDatasetMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.mapper.OrgMemberMapper;
import com.ragforge.model.entity.EvalDataset;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.security.AdminOverrideHolder;
import com.ragforge.security.KbAccessGuard;
import com.ragforge.security.OrgContextHolder;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import com.ragforge.service.JudgeQueryService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/evaluation/golden-set")
// 纳入 KB_VIEWER/USER:普通用户(如组织 owner)也应能在质量看板查看启用题数并对
// 自己有权的数据集触发回放。实际授权仍由 requireReplayPermission / requireOrgReplayPermission
// 逐组织/数据集校验(全量回放 datasetId=null 仍限平台管理员),补角色不放宽访问。
@PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR','KB_VIEWER','USER','SERVICE_ACCOUNT')")
public class GoldenSetController {

  /** 组织级单次回放题数上限：限制单次回放的判分调用规模，控制预算爆炸半径。 */
  static final int MAX_ORG_GOLDEN_QUESTIONS = 50;

  /** 每组织回放冷却窗口：防反复刷判分预算。 */
  static final Duration REPLAY_COOLDOWN = Duration.ofMinutes(5);

  private static final String COOLDOWN_KEY_PREFIX = "golden:replay:cooldown:org:";

  /** 平台基准题数：全平台视图（破玻璃）下固定展示为冻结 Core Set 的 100 题。 */
  static final int PLATFORM_BASELINE_QUESTION_COUNT = 100;

  private final GoldenSetReplayJob replayJob;
  private final JudgeQueryService judgeQueryService;
  private final EvalDatasetMapper evalDatasetMapper;
  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final OrgMemberMapper orgMemberMapper;
  private final KbAccessGuard kbAccessGuard;
  private final Executor goldenReplayExecutor;
  private final LockProvider lockProvider;
  private final StringRedisTemplate redisTemplate;

  public GoldenSetController(
      GoldenSetReplayJob replayJob,
      JudgeQueryService judgeQueryService,
      EvalDatasetMapper evalDatasetMapper,
      KnowledgeBaseMapper knowledgeBaseMapper,
      OrgMemberMapper orgMemberMapper,
      KbAccessGuard kbAccessGuard,
      @Qualifier("goldenReplayExecutor") Executor goldenReplayExecutor,
      LockProvider lockProvider,
      StringRedisTemplate redisTemplate) {
    this.replayJob = replayJob;
    this.judgeQueryService = judgeQueryService;
    this.evalDatasetMapper = evalDatasetMapper;
    this.knowledgeBaseMapper = knowledgeBaseMapper;
    this.orgMemberMapper = orgMemberMapper;
    this.kbAccessGuard = kbAccessGuard;
    this.goldenReplayExecutor = goldenReplayExecutor;
    this.lockProvider = lockProvider;
    this.redisTemplate = redisTemplate;
  }

  /** 平台级/单数据集回放（保留原语义）：datasetId=null 限平台管理员；带 datasetId 需 canRead。 */
  @PostMapping("/replay")
  public Result<ReplayResultVo> replayNow(
      @RequestParam(required = false) Long datasetId,
      @RequestParam(defaultValue = "100") int limit) {
    requireReplayPermission(datasetId);
    SimpleLock replayLock = acquireReplayLockOrThrow();

    ReplayResultVo accepted = queuedResult(Math.max(limit, 0), datasetId);
    dispatchAsync(replayLock, () -> replayJob.replay(datasetId, limit),
        () -> log.error("Manual golden set replay failed: datasetId={}, limit={}", datasetId, limit));
    return Result.ok(accepted);
  }

  /**
   * 组织级回放：当前组织的所有者/管理员（或平台管理员）触发，只回放本组织启用的黄金题， 样本落本组织。带
   * 单次上限 + 每组织冷却双护栏。
   */
  @PostMapping("/replay/org")
  public Result<ReplayResultVo> replayForCurrentOrg(
      @RequestParam(defaultValue = "50") int limit) {
    RagAuthContext ctx = RagAuthContextHolder.get();
    Long orgId = OrgContextHolder.get();
    if (ctx == null || ctx.userId() == null || orgId == null) {
      throw new BizException(400, "ORG_CONTEXT_REQUIRED");
    }
    boolean allowed = ctx.isAdmin() || orgMemberMapper.isOrgAdmin(orgId, ctx.userId());
    if (!allowed) {
      throw new BizException(403, "NOT_ORG_ADMIN");
    }
    Set<Long> scopeKbIds = orgKbIds(orgId);
    int enabled = judgeQueryService.goldenSetEnabledQuestionCount(scopeKbIds);
    if (enabled <= 0) {
      throw new BizException(400, "GOLDEN_SET_EMPTY");
    }
    int effectiveLimit = Math.min(limit <= 0 ? MAX_ORG_GOLDEN_QUESTIONS : limit, MAX_ORG_GOLDEN_QUESTIONS);

    // 护栏①：每组织冷却窗口内只允许一次
    String cooldownKey = COOLDOWN_KEY_PREFIX + orgId;
    Boolean firstInWindow = redisTemplate.opsForValue().setIfAbsent(cooldownKey, "1", REPLAY_COOLDOWN);
    if (firstInWindow == null || !firstInWindow) {
      throw new BizException(429, "GOLDEN_REPLAY_COOLDOWN");
    }

    // 护栏②：复用全局分布式锁,避免与定时/其他回放并发
    Optional<SimpleLock> lock =
        lockProvider.lock(
            new LockConfiguration(
                Instant.now(), GoldenSetReplayJob.SCHEDULER_LOCK_NAME, Duration.ofHours(2), Duration.ZERO));
    if (lock.isEmpty()) {
      redisTemplate.delete(cooldownKey); // 未真正开始,释放冷却让用户稍后可重试
      throw new BizException(409, "REPLAY_ALREADY_RUNNING");
    }
    SimpleLock replayLock = lock.get();

    ReplayResultVo accepted = queuedResult(Math.min(enabled, effectiveLimit), null);
    dispatchAsync(replayLock, () -> replayJob.replayForKbScope(scopeKbIds, effectiveLimit),
        () -> log.error("Org golden replay failed: orgId={}, limit={}", orgId, effectiveLimit));
    return Result.ok(accepted);
  }

  /** 启用题数：平台破玻璃返回固定平台基准 100（冻结 Core Set）；否则只统计当前组织范围内的启用黄金题。 */
  @GetMapping("/enabled-count")
  public Result<Integer> enabledCount() {
    RagAuthContext ctx = RagAuthContextHolder.get();
    if (ctx != null && ctx.isAdmin() && AdminOverrideHolder.isActive()) {
      return Result.ok(PLATFORM_BASELINE_QUESTION_COUNT);
    }
    return Result.ok(judgeQueryService.goldenSetEnabledQuestionCount(orgKbIds(OrgContextHolder.get())));
  }

  // ---- helpers ----

  private ReplayResultVo queuedResult(int requested, Long datasetId) {
    ReplayResultVo vo = new ReplayResultVo();
    vo.setRequested(Math.max(requested, 0));
    vo.setDatasetId(datasetId);
    vo.setMessage("queued");
    vo.setSuccess(0);
    vo.setFailed(0);
    vo.setStartedAt(System.currentTimeMillis());
    return vo;
  }

  private SimpleLock acquireReplayLockOrThrow() {
    Optional<SimpleLock> lock =
        lockProvider.lock(
            new LockConfiguration(
                Instant.now(), GoldenSetReplayJob.SCHEDULER_LOCK_NAME, Duration.ofHours(2), Duration.ZERO));
    if (lock.isEmpty()) {
      throw new BizException(409, "REPLAY_ALREADY_RUNNING");
    }
    return lock.get();
  }

  private void dispatchAsync(SimpleLock replayLock, Runnable job, Runnable onError) {
    try {
      CompletableFuture.runAsync(
          () -> {
            try {
              job.run();
            } catch (Exception e) {
              onError.run();
            } finally {
              replayLock.unlock();
            }
          },
          goldenReplayExecutor);
    } catch (RuntimeException e) {
      replayLock.unlock();
      throw e;
    }
  }

  /** 当前组织自有的 KB id 集（不含公开库；黄金题按 dataset 归属 KB 判定组织）。 */
  private Set<Long> orgKbIds(Long orgId) {
    if (orgId == null) {
      return Set.of();
    }
    return knowledgeBaseMapper
        .selectList(
            new LambdaQueryWrapper<KnowledgeBase>()
                .ne(KnowledgeBase::getStatus, "deleted")
                .eq(KnowledgeBase::getOrgId, orgId))
        .stream()
        .map(KnowledgeBase::getId)
        .collect(Collectors.toSet());
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
