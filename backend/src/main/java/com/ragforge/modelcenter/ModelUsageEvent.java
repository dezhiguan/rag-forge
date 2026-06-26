package com.ragforge.modelcenter;

/** 一次模型调用的用量事件，由各调用点在计量点并联上报。 */
public record ModelUsageEvent(
    String modelCode,
    Purpose purpose,
    long inputTokens,
    long outputTokens,
    long latencyMs,
    boolean success) {}
