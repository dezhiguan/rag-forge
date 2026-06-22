package com.ragforge.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import io.micrometer.core.instrument.Tags;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class RagforgeMetrics {

  private final MeterRegistry meterRegistry;
  private final ConcurrentHashMap<String, AtomicReference<Double>> judgeScoreGauges =
      new ConcurrentHashMap<>();

  public RagforgeMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public void recordIngestCreated() {
    meterRegistry.counter("ragforge.ingest.created").increment();
  }

  public void recordIngestSkipped() {
    meterRegistry.counter("ragforge.ingest.skipped").increment();
  }

  public void recordIngestReplaced() {
    meterRegistry.counter("ragforge.ingest.replaced").increment();
  }

  public void recordWorkerDuration(String modality, long nanos) {
    meterRegistry
        .timer("ragforge.worker.processing_duration", "modality", label(modality))
        .record(nanos, TimeUnit.NANOSECONDS);
  }

  public void recordWorkerFailed(String reason) {
    meterRegistry.counter("ragforge.worker.failed", "reason", label(reason)).increment();
  }

  public void recordVlEmbeddingCall(int inputCount) {
    meterRegistry.counter("ragforge.embedding.vl.calls").increment();
    meterRegistry
        .counter("ragforge.embedding.vl.tokens", "type", "input_tokens")
        .increment(Math.max(0, inputCount));
  }

  public void recordVlEmbeddingImageTokens(int imageCount) {
    meterRegistry
        .counter("ragforge.embedding.vl.tokens", "type", "image_tokens")
        .increment(Math.max(0, imageCount));
  }

  public void recordOcrCall(int imageTokens, int outputTokens) {
    meterRegistry.counter("ragforge.ocr.qwen_vl_ocr.calls").increment();
    meterRegistry
        .counter("ragforge.ocr.qwen_vl_ocr.tokens", "type", "image_tokens")
        .increment(Math.max(0, imageTokens));
    meterRegistry
        .counter("ragforge.ocr.qwen_vl_ocr.tokens", "type", "output_tokens")
        .increment(Math.max(0, outputTokens));
  }

  public void recordAnswerTokens(int promptTokens, int completionTokens) {
    meterRegistry
        .counter("ragforge.answer.tokens", "type", "prompt")
        .increment(Math.max(0, promptTokens));
    meterRegistry
        .counter("ragforge.answer.tokens", "type", "completion")
        .increment(Math.max(0, completionTokens));
  }

  public void updateAnswerCitationRate(int citationCount, int resultCount) {
    meterRegistry
        .counter("ragforge.answer.citations_total", "kb", "default")
        .increment(Math.max(0, citationCount));
    if (resultCount > 0) {
      meterRegistry
          .counter("ragforge.answer.retrieval_results_total", "kb", "default")
          .increment(resultCount);
    }
  }

  public void recordAnswerGuardRailBlocked(String reason) {
    meterRegistry.counter("ragforge.answer.guard_rail.blocked", "reason", label(reason)).increment();
  }

  public void recordKbAccessDenied(String operation) {
    meterRegistry.counter("ragforge.authz.kb_access_denied", "operation", label(operation)).increment();
    meterRegistry.counter("ragforge.kb_access_denied", "operation", label(operation)).increment();
  }

  public void recordSearchLatency(String strategy, long millis) {
    meterRegistry
        .timer("ragforge.search.latency", "strategy", label(strategy))
        .record(Math.max(0, millis), TimeUnit.MILLISECONDS);
  }

  public void recordJudgeRequests(String source) {
    meterRegistry.counter("ragforge.judge.requests", "source", label(source)).increment();
  }

  public void recordJudgeDuration(String source, long nanos) {
    meterRegistry
        .timer("ragforge.judge.duration", "source", label(source))
        .record(Math.max(0L, nanos), TimeUnit.NANOSECONDS);
  }

  public void recordJudgeFailed(String source, String reason) {
    meterRegistry
        .counter("ragforge.judge.failed", "source", label(source), "reason", label(reason))
        .increment();
  }

  public void recordJudgeScore(String dimension, Long kbId, BigDecimal score) {
    if (dimension == null || score == null) {
      return;
    }
    String dimensionTag = label(dimension);
    String kbTag = kbId == null ? "-1" : String.valueOf(kbId);
    String gaugeName = "ragforge.judge.score";
    String key = dimensionTag + ":" + kbTag;
    AtomicReference<Double> holder = judgeScoreGauges.get(key);
    if (holder == null) {
      holder = new AtomicReference<>(0.0);
      AtomicReference<Double> finalHolder = holder;
      meterRegistry.gauge(
          gaugeName,
          Tags.of("dimension", dimensionTag, "kb_id", kbTag),
          finalHolder,
          state -> state.get() == null ? 0.0 : state.get());
      judgeScoreGauges.put(key, holder);
    }
    holder.set(score.doubleValue());
  }

  public void recordJudgeCost(String source, BigDecimal cost) {
    if (cost == null) {
      return;
    }
    meterRegistry
        .counter("ragforge.judge.cost", "source", label(source))
        .increment(cost.max(BigDecimal.ZERO).doubleValue());
  }

  public void recordDeepSeekTokens(int promptTokens, int completionTokens) {
    meterRegistry
        .counter("ragforge.deepseek.tokens", "type", "prompt")
        .increment(Math.max(0, promptTokens));
    meterRegistry
        .counter("ragforge.deepseek.tokens", "type", "completion")
        .increment(Math.max(0, completionTokens));
  }

  private static String label(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }
}
