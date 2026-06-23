package com.ragforge.judge.sampler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ragforge.mapper.JudgeSamplingConfigMapper;
import com.ragforge.model.entity.JudgeSamplingConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class JudgeSampler {

  private static final String REASON_KEEP_BY_FORCE = "KEEP_BY_FORCE";
  private static final String REASON_KEEP_BY_GOLDEN = "KEEP_BY_GOLDEN";
  private static final String REASON_KEEP_BY_RATE = "KEEP_BY_RATE";
  private static final String REASON_SKIP_BY_RATE = "SKIP_BY_RATE";
  private static final String REASON_SKIP_DISABLED = "SKIP_DISABLED";

  private final JudgeSamplingConfigMapper configMapper;

  @Value("${ragforge.judge.sampling-fallback-rate:0.0}")
  private BigDecimal samplingFallbackRate;

  public SampleDecision decide(SampleRequest request) {
    if ("GOLDEN_SET".equalsIgnoreCase(request.source())) {
      return new SampleDecision(true, null, getFallbackOrNull(), REASON_KEEP_BY_GOLDEN);
    }
    if (request.forceSample()) {
      return new SampleDecision(true, null, getFallbackOrNull(), REASON_KEEP_BY_FORCE);
    }

    JudgeSamplingConfig selected = selectConfig(request);
    BigDecimal sampleRate = normalizeRate(selected == null ? samplingFallbackRate : selected.getSampleRate());
    Long configId = selected == null ? null : selected.getId();

    if (selected != null && Boolean.FALSE.equals(selected.getEnabled())) {
      return new SampleDecision(false, configId, sampleRate, REASON_SKIP_DISABLED);
    }

    boolean keep = shouldKeepByRate(sampleRate);
    String reason = keep ? REASON_KEEP_BY_RATE : REASON_SKIP_BY_RATE;
    return new SampleDecision(keep, configId, sampleRate, reason);
  }

  private JudgeSamplingConfig selectConfig(SampleRequest request) {
    boolean hasKbIds = request.kbIds() != null && request.kbIds().length > 0;
    boolean hasTenant = request.tenantId() != null && !request.tenantId().isBlank();
    final boolean[] firstCondition = {true};
    QueryWrapper<JudgeSamplingConfig> query = new QueryWrapper<>();
    query.and(
            qw -> {
              if (hasKbIds) {
                qw.eq("scope_type", "KB").in("scope_id", Arrays.asList(request.kbIds()));
                firstCondition[0] = false;
              }
              if (hasTenant) {
                if (!firstCondition[0]) {
                  qw.or();
                }
                qw.eq("scope_type", "TENANT").eq("tenant_id", request.tenantId());
                firstCondition[0] = false;
              }
              if (!firstCondition[0]) {
                qw.or();
              }
              qw.eq("scope_type", "GLOBAL");
            })
        .in("scope_type", List.of("KB", "TENANT", "GLOBAL"))
        .last("ORDER BY CASE WHEN scope_type='KB' THEN 0 WHEN scope_type='TENANT' THEN 1 ELSE 2 END, id ASC LIMIT 1");

    List<JudgeSamplingConfig> matches = configMapper.selectList(query);
    if (matches == null) {
      return null;
    }
    return matches.isEmpty() ? null : matches.get(0);
  }

  private boolean shouldKeepByRate(BigDecimal sampleRate) {
    BigDecimal rate = normalizeRate(sampleRate);
    if (rate.compareTo(BigDecimal.ZERO) <= 0) {
      return false;
    }
    if (rate.compareTo(BigDecimal.ONE) >= 0) {
      return true;
    }
    return ThreadLocalRandom.current().nextDouble() < rate.doubleValue();
  }

  private BigDecimal normalizeRate(BigDecimal rate) {
    if (rate == null) {
      return BigDecimal.ZERO;
    }
    BigDecimal bounded = rate.setScale(4, RoundingMode.DOWN);
    if (bounded.compareTo(BigDecimal.ZERO) < 0) {
      return BigDecimal.ZERO;
    }
    if (bounded.compareTo(BigDecimal.ONE) > 0) {
      return BigDecimal.ONE;
    }
    return bounded;
  }

  private BigDecimal getFallbackOrNull() {
    return normalizeRate(samplingFallbackRate);
  }
}
