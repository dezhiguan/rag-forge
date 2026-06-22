package com.ragforge.judge.sampler;

public record SampleRequest(
    Long answerLogId,
    Long[] kbIds,
    String tenantId,
    String source,
    boolean forceSample) {}

