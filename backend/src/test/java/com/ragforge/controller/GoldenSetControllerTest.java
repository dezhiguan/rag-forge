package com.ragforge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.ragforge.common.GlobalExceptionHandler;
import com.ragforge.judge.GoldenSetReplayJob;
import com.ragforge.mapper.EvalDatasetMapper;
import com.ragforge.model.entity.EvalDataset;
import com.ragforge.security.KbAccessGuard;
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
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

@ExtendWith(MockitoExtension.class)
class GoldenSetControllerTest {

  @Mock private GoldenSetReplayJob replayJob;
  @Mock private JudgeQueryService judgeQueryService;
  @Mock private EvalDatasetMapper evalDatasetMapper;
  @Mock private KbAccessGuard kbAccessGuard;

  private final List<Runnable> queuedTasks = new ArrayList<>();
  private final FakeLockProvider lockProvider = new FakeLockProvider();
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    Executor queuedExecutor = queuedTasks::add;
    GoldenSetController controller =
        new GoldenSetController(
            replayJob, judgeQueryService, evalDatasetMapper, kbAccessGuard, queuedExecutor, lockProvider);
    mockMvc =
        standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();
  }

  @AfterEach
  void tearDown() {
    RagAuthContextHolder.clear();
  }

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
        new RagAuthContext(42L, "tn_1", "KB_EDITOR", Set.of(99L), Set.of(99L), Set.of(), "USER", "42"));

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

  private static EvalDataset dataset(Long id, Long kbId) {
    EvalDataset dataset = new EvalDataset();
    dataset.setId(id);
    dataset.setKbId(kbId);
    return dataset;
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
