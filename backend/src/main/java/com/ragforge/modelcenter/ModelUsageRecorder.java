package com.ragforge.modelcenter;

import com.ragforge.mapper.ModelUsageDailyMapper;
import com.ragforge.model.entity.ModelConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 模型用量计量器：各调用点在计量点并联上报 {@link ModelUsageEvent}。
 *
 * <p>{@link #record} 仅入有界内存队列、非阻塞，绝不在业务线程同步写库；队列满则丢弃并计数。
 * {@link #flush()} 每 5s 批量聚合后 UPSERT 累加到 model_usage_daily。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelUsageRecorder {

  private static final int QUEUE_CAPACITY = 20_000;
  private static final int MAX_BATCH = 5_000;

  private final BlockingQueue<ModelUsageEvent> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
  private final AtomicLong dropped = new AtomicLong();

  private final ModelUsageDailyMapper usageMapper;
  private final ModelResolver modelResolver;
  private final CostCalculator costCalculator;

  /** 非阻塞上报；队列满则丢弃并累加计数（业务线程绝不阻塞）。 */
  public void record(ModelUsageEvent event) {
    if (event == null || event.modelCode() == null || event.purpose() == null) {
      return;
    }
    if (!queue.offer(event)) {
      long n = dropped.incrementAndGet();
      if (n % 1000 == 1) {
        log.warn("ModelUsageRecorder queue full, dropped {} events so far", n);
      }
    }
  }

  @Scheduled(fixedDelay = 5_000)
  public void flush() {
    List<ModelUsageEvent> batch = new ArrayList<>(MAX_BATCH);
    if (queue.drainTo(batch, MAX_BATCH) == 0) {
      return;
    }
    LocalDate today = LocalDate.now();
    Map<String, Agg> aggregated = new LinkedHashMap<>();
    for (ModelUsageEvent e : batch) {
      Agg a = aggregated.computeIfAbsent(e.modelCode(), k -> new Agg(e.purpose().name()));
      a.calls++;
      a.inputTokens += Math.max(0, e.inputTokens());
      a.outputTokens += Math.max(0, e.outputTokens());
      a.latencyMs += Math.max(0, e.latencyMs());
      if (e.success()) {
        a.success++;
      } else {
        a.fail++;
      }
      ModelConfig cfg = modelResolver.findByCode(e.modelCode());
      a.cost = a.cost.add(costCalculator.compute(cfg, Math.max(0, e.inputTokens()), Math.max(0, e.outputTokens())));
    }
    int failedUpserts = 0;
    for (Map.Entry<String, Agg> entry : aggregated.entrySet()) {
      Agg a = entry.getValue();
      try {
        usageMapper.upsertAccumulate(
            entry.getKey(),
            a.purpose,
            today,
            a.calls,
            a.inputTokens,
            a.outputTokens,
            a.cost,
            a.success,
            a.fail,
            a.latencyMs);
      } catch (Exception ex) {
        failedUpserts++;
        log.warn("ModelUsageRecorder upsert failed for model={}: {}", entry.getKey(), ex.getMessage());
      }
    }
    if (log.isDebugEnabled()) {
      log.debug(
          "ModelUsageRecorder flushed events={} models={} failedUpserts={}",
          batch.size(), aggregated.size(), failedUpserts);
    }
  }

  private static final class Agg {
    final String purpose;
    long calls;
    long inputTokens;
    long outputTokens;
    long latencyMs;
    long success;
    long fail;
    BigDecimal cost = BigDecimal.ZERO;

    Agg(String purpose) {
      this.purpose = purpose;
    }
  }
}
