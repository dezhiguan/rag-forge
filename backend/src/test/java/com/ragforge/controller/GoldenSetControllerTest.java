package com.ragforge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.ragforge.common.GlobalExceptionHandler;
import com.ragforge.judge.GoldenSetReplayJob;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

@ExtendWith(MockitoExtension.class)
class GoldenSetControllerTest {

  @Mock private GoldenSetReplayJob replayJob;
  @Mock private JudgeQueryService judgeQueryService;
  @Mock private EvalDatasetMapper evalDatasetMapper;
  @Mock private KnowledgeBaseMapper knowledgeBaseMapper;
  @Mock private OrgMemberMapper orgMemberMapper;
  @Mock private KbAccessGuard kbAccessGuard;
  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOps;
  @Mock private com.ragforge.judge.JudgeBudgetService budgetService;

  private final List<Runnable> queuedTasks = new ArrayList<>();
  private final FakeLockProvider lockProvider = new FakeLockProvider();
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    Executor queuedExecutor = queuedTasks::add;
    GoldenSetController controller =
        new GoldenSetController(
            replayJob,
            judgeQueryService,
            evalDatasetMapper,
            knowledgeBaseMapper,
            orgMemberMapper,
            kbAccessGuard,
            queuedExecutor,
            lockProvider,
            redisTemplate,
            budgetService);
    mockMvc =
        standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();
  }

  @AfterEach
  void tearDown() {
    RagAuthContextHolder.clear();
    OrgContextHolder.clear();
    AdminOverrideHolder.clear();
  }

  // ===================== 原 /replay 语义（保持不变）=====================

  @Test
  void replayNow_无权datasetKB_返回403() throws Exception {
    when(evalDatasetMapper.selectById(7L)).thenReturn(dataset(7L, 99L));
    when(kbAccessGuard.canRead(99L)).thenReturn(false);

    mockMvc
        .perform(post("/api/v1/evaluation/golden-set/replay?datasetId=7&limit=5"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    verify(replayJob, never()).replay(7L, 5);
  }

  @Test
  void replayNow_无datasetId_KB_EDITOR_返回403() throws Exception {
    RagAuthContextHolder.set(
        new RagAuthContext(42L, "KB_EDITOR", Set.of(99L), Set.of(99L), Set.of(), "USER", "42"));

    mockMvc
        .perform(post("/api/v1/evaluation/golden-set/replay?limit=5"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    verify(replayJob, never()).replay(null, 5);
  }

  @Test
  void replayNow_并发触发_第二个返回409() throws Exception {
    when(evalDatasetMapper.selectById(7L)).thenReturn(dataset(7L, 99L));
    when(kbAccessGuard.canRead(99L)).thenReturn(true);

    mockMvc
        .perform(post("/api/v1/evaluation/golden-set/replay?datasetId=7&limit=5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.message").value("queued"));

    mockMvc
        .perform(post("/api/v1/evaluation/golden-set/replay?datasetId=7&limit=5"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409));

    assertThat(queuedTasks).hasSize(1);
    assertThat(lockProvider.lastLockName).isEqualTo(GoldenSetReplayJob.SCHEDULER_LOCK_NAME);
  }

  @Test
  void replayNow_cron持有分布式锁_手动触发返回409() throws Exception {
    when(evalDatasetMapper.selectById(7L)).thenReturn(dataset(7L, 99L));
    when(kbAccessGuard.canRead(99L)).thenReturn(true);
    lockProvider.lock(
        new LockConfiguration(
            Instant.now(), GoldenSetReplayJob.SCHEDULER_LOCK_NAME, Duration.ofHours(2), Duration.ZERO));

    mockMvc
        .perform(post("/api/v1/evaluation/golden-set/replay?datasetId=7&limit=5"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.msg").value("REPLAY_ALREADY_RUNNING"));

    verify(replayJob, never()).replay(7L, 5);
  }

  // ===================== 组织级 /replay/org =====================

  @Test
  void replayOrg_无组织上下文_返回400() throws Exception {
    RagAuthContextHolder.set(userCtx(42L));
    // OrgContextHolder 未设置 → orgId=null

    mockMvc
        .perform(post("/api/v1/evaluation/golden-set/replay/org"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("ORG_CONTEXT_REQUIRED"));
  }

  @Test
  void replayOrg_非组织管理员_返回403() throws Exception {
    RagAuthContextHolder.set(userCtx(42L));
    OrgContextHolder.set(5L);
    when(orgMemberMapper.isOrgAdmin(5L, 42L)).thenReturn(false);

    mockMvc
        .perform(post("/api/v1/evaluation/golden-set/replay/org"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("NOT_ORG_ADMIN"));

    verify(replayJob, never()).replayForKbScope(anySet(), eq(50));
  }

  @Test
  void replayOrg_无启用黄金题_返回400_友好提示() throws Exception {
    RagAuthContextHolder.set(userCtx(42L));
    OrgContextHolder.set(5L);
    when(orgMemberMapper.isOrgAdmin(5L, 42L)).thenReturn(true);
    when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(kb(99L, 5L)));
    when(judgeQueryService.goldenSetEnabledQuestionCount(anySet())).thenReturn(0);

    mockMvc
        .perform(post("/api/v1/evaluation/golden-set/replay/org"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("GOLDEN_SET_EMPTY"))
        .andExpect(jsonPath("$.msg").value("当前组织还没有启用黄金题，请先到评测数据集勾选后再回放"));

    assertThat(queuedTasks).isEmpty();
  }

  @Test
  void replayOrg_组织管理员_成功排队并设置冷却() throws Exception {
    RagAuthContextHolder.set(userCtx(42L));
    OrgContextHolder.set(5L);
    when(orgMemberMapper.isOrgAdmin(5L, 42L)).thenReturn(true);
    when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(kb(99L, 5L)));
    when(judgeQueryService.goldenSetEnabledQuestionCount(anySet())).thenReturn(12);
    when(budgetService.isExceeded(anyLong(), anySet())).thenReturn(false);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.setIfAbsent(anyString(), eq("1"), eq(GoldenSetController.REPLAY_COOLDOWN)))
        .thenReturn(true);

    mockMvc
        .perform(post("/api/v1/evaluation/golden-set/replay/org?limit=100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.message").value("queued"))
        // limit=100 被单次上限 50 收窄，enabled=12 → requested=min(12,50)=12
        .andExpect(jsonPath("$.data.requested").value(12));

    assertThat(queuedTasks).hasSize(1);
  }

  @Test
  void replayOrg_冷却窗口内_返回429() throws Exception {
    RagAuthContextHolder.set(userCtx(42L));
    OrgContextHolder.set(5L);
    when(orgMemberMapper.isOrgAdmin(5L, 42L)).thenReturn(true);
    when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(kb(99L, 5L)));
    when(judgeQueryService.goldenSetEnabledQuestionCount(anySet())).thenReturn(12);
    when(budgetService.isExceeded(anyLong(), anySet())).thenReturn(false);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.setIfAbsent(anyString(), eq("1"), eq(GoldenSetController.REPLAY_COOLDOWN)))
        .thenReturn(false);

    mockMvc
        .perform(post("/api/v1/evaluation/golden-set/replay/org"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.errorCode").value("GOLDEN_REPLAY_COOLDOWN"));

    assertThat(queuedTasks).isEmpty();
  }

  @Test
  void replayOrg_锁被占用_返回409并释放冷却() throws Exception {
    RagAuthContextHolder.set(userCtx(42L));
    OrgContextHolder.set(5L);
    when(orgMemberMapper.isOrgAdmin(5L, 42L)).thenReturn(true);
    when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(kb(99L, 5L)));
    when(judgeQueryService.goldenSetEnabledQuestionCount(anySet())).thenReturn(12);
    when(budgetService.isExceeded(anyLong(), anySet())).thenReturn(false);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.setIfAbsent(anyString(), eq("1"), eq(GoldenSetController.REPLAY_COOLDOWN)))
        .thenReturn(true);
    // 占用全局锁
    lockProvider.lock(
        new LockConfiguration(
            Instant.now(), GoldenSetReplayJob.SCHEDULER_LOCK_NAME, Duration.ofHours(2), Duration.ZERO));

    mockMvc
        .perform(post("/api/v1/evaluation/golden-set/replay/org"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.msg").value("REPLAY_ALREADY_RUNNING"));

    verify(redisTemplate).delete(anyString());
    assertThat(queuedTasks).isEmpty();
  }

  @Test
  void replayOrg_平台管理员_跳过组织角色校验也可触发() throws Exception {
    RagAuthContextHolder.set(adminCtx(1L));
    OrgContextHolder.set(5L);
    when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(kb(99L, 5L)));
    when(judgeQueryService.goldenSetEnabledQuestionCount(anySet())).thenReturn(8);
    when(budgetService.isExceeded(anyLong(), anySet())).thenReturn(false);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.setIfAbsent(anyString(), eq("1"), eq(GoldenSetController.REPLAY_COOLDOWN)))
        .thenReturn(true);

    mockMvc
        .perform(post("/api/v1/evaluation/golden-set/replay/org"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.requested").value(8));

    assertThat(queuedTasks).hasSize(1);
    verify(orgMemberMapper, never()).isOrgAdmin(anyLong(), anyLong());
  }

  @Test
  void replayOrg_预算超支_返回403拦截() throws Exception {
    RagAuthContextHolder.set(userCtx(42L));
    OrgContextHolder.set(5L);
    when(orgMemberMapper.isOrgAdmin(5L, 42L)).thenReturn(true);
    when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(kb(99L, 5L)));
    when(judgeQueryService.goldenSetEnabledQuestionCount(anySet())).thenReturn(12);
    when(budgetService.isExceeded(anyLong(), anySet())).thenReturn(true);

    mockMvc
        .perform(post("/api/v1/evaluation/golden-set/replay/org"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("GOLDEN_BUDGET_EXCEEDED"))
        .andExpect(jsonPath("$.msg").value("本月评测额度已用完，请联系平台管理员或下月再试"));

    assertThat(queuedTasks).isEmpty();
    verify(replayJob, never()).replayForKbScope(anySet(), eq(50));
  }

  // ===================== /enabled-count 组织级 =====================

  @Test
  void enabledCount_组织口径_返回本组织启用数() throws Exception {
    RagAuthContextHolder.set(userCtx(42L));
    OrgContextHolder.set(5L);
    when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(kb(99L, 5L)));
    when(judgeQueryService.goldenSetEnabledQuestionCount(anySet())).thenReturn(12);

    mockMvc
        .perform(get("/api/v1/evaluation/golden-set/enabled-count"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(12));
  }

  @Test
  void enabledCount_平台破玻璃_返回固定平台基准100() throws Exception {
    RagAuthContextHolder.set(adminCtx(1L));
    AdminOverrideHolder.activate("governance");

    mockMvc
        .perform(get("/api/v1/evaluation/golden-set/enabled-count"))
        .andExpect(status().isOk())
        // 平台基准固定 100，不查库（不随实际启用数漂移）
        .andExpect(jsonPath("$.data").value(100));

    verify(judgeQueryService, never()).goldenSetEnabledQuestionCount(anySet());
  }

  // ===================== helpers =====================

  private static RagAuthContext userCtx(Long userId) {
    return new RagAuthContext(userId, "USER", Set.of(), Set.of(), Set.of(), "USER", String.valueOf(userId));
  }

  private static RagAuthContext adminCtx(Long userId) {
    return new RagAuthContext(userId, "ADMIN", Set.of(), Set.of(), Set.of(), "USER", String.valueOf(userId));
  }

  private static EvalDataset dataset(Long id, Long kbId) {
    EvalDataset dataset = new EvalDataset();
    dataset.setId(id);
    dataset.setKbId(kbId);
    return dataset;
  }

  private static KnowledgeBase kb(Long id, Long orgId) {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(id);
    kb.setOrgId(orgId);
    kb.setStatus("active");
    return kb;
  }

  private static class FakeLockProvider implements LockProvider {
    private boolean locked;
    private String lastLockName;

    @Override
    public Optional<SimpleLock> lock(LockConfiguration lockConfiguration) {
      lastLockName = lockConfiguration.getName();
      if (locked) {
        return Optional.empty();
      }
      locked = true;
      return Optional.of(() -> locked = false);
    }
  }
}
