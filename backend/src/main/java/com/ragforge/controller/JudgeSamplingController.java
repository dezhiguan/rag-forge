package com.ragforge.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.common.BizException;
import com.ragforge.common.Result;
import com.ragforge.judge.SamplingUpsertRequest;
import com.ragforge.mapper.JudgeSamplingConfigMapper;
import com.ragforge.model.entity.JudgeSamplingConfig;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/evaluation/quality/sampling")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class JudgeSamplingController {

  private static final BigDecimal MAX_RATE = new BigDecimal("1.0");
  private static final BigDecimal MIN_RATE = BigDecimal.ZERO;
  private static final BigDecimal RATE_CONFIRM_THRESHOLD = new BigDecimal("0.1");

  private final JudgeSamplingConfigMapper configMapper;

  @GetMapping
  public Result<List<JudgeSamplingConfig>> list() {
    List<JudgeSamplingConfig> list =
        configMapper.selectList(new LambdaQueryWrapper<JudgeSamplingConfig>().orderByAsc(JudgeSamplingConfig::getScopeType));
    return Result.ok(list);
  }

  @PostMapping
  public Result<JudgeSamplingConfig> upsert(@RequestBody SamplingUpsertRequest req) {
    normalizeReq(req);
    validateReq(req);

    LambdaQueryWrapper<JudgeSamplingConfig> query = buildLookup(req.getScopeType(), req.getScopeId(), req.getTenantId());
    JudgeSamplingConfig config = configMapper.selectOne(query);
    if (config == null) {
      config = new JudgeSamplingConfig();
    }
    config.setScopeType(req.getScopeType());
    config.setScopeId(normalizeScopeId(req));
    config.setTenantId(normalizeTenantId(req));
    config.setSampleRate(req.getSampleRate());
    config.setEnabled(Boolean.TRUE.equals(req.getEnabled()));
    config.setUpdatedAt(LocalDateTime.now());
    config.setUpdatedBy(updatedBy());

    if (config.getId() != null) {
      configMapper.updateById(config);
      return Result.ok(config);
    }

    configMapper.insert(config);
    return Result.ok(config);
  }

  @DeleteMapping("/{id}")
  public Result<Void> delete(@PathVariable Long id) {
    configMapper.deleteById(id);
    return Result.ok();
  }

  private void normalizeReq(SamplingUpsertRequest req) {
    if (req == null) {
      return;
    }
    if (req.getScopeType() != null) {
      req.setScopeType(req.getScopeType().trim().toUpperCase(Locale.ROOT));
    }
    if (req.getTenantId() != null) {
      req.setTenantId(req.getTenantId().trim());
    }
  }

  private void validateReq(SamplingUpsertRequest req) {
    if (req == null) {
      throw new BizException(400, "INVALID_REQUEST");
    }
    if (req.getSampleRate() == null || req.getSampleRate().compareTo(MIN_RATE) < 0 || req.getSampleRate().compareTo(MAX_RATE) > 0) {
      throw new BizException(400, "SAMPLE_RATE_OUT_OF_RANGE");
    }
    if (req.getSampleRate().compareTo(RATE_CONFIRM_THRESHOLD) > 0 && !req.isConfirmed()) {
      throw new BizException(400, "SAMPLE_RATE_TOO_HIGH_REQUIRES_CONFIRM");
    }
    if (!"GLOBAL".equals(req.getScopeType())
        && !"KB".equals(req.getScopeType())
        && !"TENANT".equals(req.getScopeType())) {
      throw new BizException(400, "INVALID_SCOPE_TYPE");
    }
    if ("KB".equals(req.getScopeType()) && req.getScopeId() == null) {
      throw new BizException(400, "SCOPE_ID_REQUIRED");
    }
    if ("TENANT".equals(req.getScopeType()) && (req.getTenantId() == null || req.getTenantId().isBlank())) {
      throw new BizException(400, "TENANT_ID_REQUIRED");
    }
  }

  private String updatedBy() {
    RagAuthContext context = RagAuthContextHolder.get();
    return context == null || context.principalId() == null ? "system" : context.principalId();
  }

  private LambdaQueryWrapper<JudgeSamplingConfig> buildLookup(String scopeType, Long scopeId, String tenantId) {
    LambdaQueryWrapper<JudgeSamplingConfig> query = new LambdaQueryWrapper<>();
    query.eq(JudgeSamplingConfig::getScopeType, scopeType);
    if ("GLOBAL".equals(scopeType)) {
      query.isNull(JudgeSamplingConfig::getScopeId).isNull(JudgeSamplingConfig::getTenantId);
    } else if ("KB".equals(scopeType)) {
      query.eq(JudgeSamplingConfig::getScopeId, scopeId);
    } else {
      query.eq(JudgeSamplingConfig::getTenantId, tenantId);
    }
    return query;
  }

  private Long normalizeScopeId(SamplingUpsertRequest req) {
    return "KB".equals(req.getScopeType()) ? req.getScopeId() : null;
  }

  private String normalizeTenantId(SamplingUpsertRequest req) {
    return "TENANT".equals(req.getScopeType()) ? req.getTenantId() : null;
  }
}
