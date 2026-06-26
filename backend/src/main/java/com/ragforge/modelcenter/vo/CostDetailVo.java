package com.ragforge.modelcenter.vo;

import java.math.BigDecimal;

/** 按用途的调用明细行。 */
public record CostDetailVo(
    String purpose,
    String modelCode,
    long callCount,
    long inputTokens,
    long outputTokens,
    BigDecimal cost,
    long avgLatencyMs) {}
