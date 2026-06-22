package com.ragforge.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class RagforgeMetrics {

  private final MeterRegistry meterRegistry;
  private final AtomicReference<Double> answerCitationRate = new AtomicReference<>(0.0);

  public RagforgeMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    meterRegistry.gauge("ragforge.answer.citation_rate", answerCitationRate, AtomicReference::get);
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
    if (resultCount <= 0) {
      answerCitationRate.set(citationCount > 0 ? 1.0 : 0.0);
      return;
    }
    answerCitationRate.set(Math.min(1.0, Math.max(0.0, citationCount / (double) resultCount)));
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

  private static String label(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }
}
