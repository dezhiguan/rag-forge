package com.ragforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ragforge.eval")
public class EvalProperties {

  // 逐题并发上限（全局共享该池 → 即全平台同时在跑的题目数上限）。1g 堆下 rewrite 4 变体 fan-out
  // 内存放大明显，压回 3 防 OOM（2026-07-07 消融并发压垮 api 堆事故）。
  private int maxConcurrentQuestions = 3;
  private long questionTimeoutMs = 45000;
  // running 实验超过该时长（分钟）仍未终结，视为进程崩溃/重启残留，由 reaper 置 failed。
  private int stuckRunningMinutes = 15;
}
