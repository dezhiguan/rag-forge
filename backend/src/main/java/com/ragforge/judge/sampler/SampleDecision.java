package com.ragforge.judge.sampler;

import java.math.BigDecimal;

public record SampleDecision(
    boolean keep,
    Long configId,
    BigDecimal effectiveSampleRate,
    String reason) {}

