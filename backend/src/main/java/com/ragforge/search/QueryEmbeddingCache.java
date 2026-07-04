package com.ragforge.search;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Query 向量缓存。
 *
 * <p>检索链路最大头延迟是 DashScope query-embedding（实测 ~150-490ms，占单请求 90%+，Qdrant 仅 5-62ms）。
 * 相同查询（热点问题、重复检索）命中缓存后直接复用向量，砍掉该延迟，vector/hybrid 的 QPS 显著提升。
 *
 * <p>仅缓存<b>查询</b>向量：文档入库走 {@code embedBatch} 不经此，避免污染。进程内 Caffeine，
 * 容量上限 + 写入 TTL 兜底，带开关（{@code ragforge.retrieval.query-embedding-cache.enabled}）。
 */
@Slf4j
@Component
public class QueryEmbeddingCache {

  private final boolean enabled;
  private final Cache<String, float[]> cache;
  private final MeterRegistry meterRegistry;

  public QueryEmbeddingCache(
      @Value("${ragforge.retrieval.query-embedding-cache.enabled:true}") boolean enabled,
      @Value("${ragforge.retrieval.query-embedding-cache.max-size:20000}") long maxSize,
      @Value("${ragforge.retrieval.query-embedding-cache.ttl-seconds:900}") long ttlSeconds,
      MeterRegistry meterRegistry) {
    this.enabled = enabled;
    this.meterRegistry = meterRegistry;
    this.cache =
        enabled
            ? Caffeine.newBuilder()
                .maximumSize(Math.max(1, maxSize))
                .expireAfterWrite(Duration.ofSeconds(Math.max(1, ttlSeconds)))
                .recordStats()
                .build()
            : null;
    log.info(
        "QueryEmbeddingCache enabled={} maxSize={} ttlSeconds={}", enabled, maxSize, ttlSeconds);
  }

  /**
   * 取查询向量：命中返回缓存，未命中用 loader 计算并写入。禁用或空查询直接透传。
   *
   * <p>返回的向量在检索链路中<b>只读</b>使用（传给 Qdrant 检索），不复制以省开销。
   */
  public float[] get(String query, Function<String, float[]> loader) {
    if (!enabled || query == null || query.isBlank()) {
      return loader.apply(query);
    }
    String key = query.strip();
    float[] cached = cache.getIfPresent(key);
    if (cached != null) {
      meterRegistry
          .counter("ragforge.retrieval.query_embedding_cache", "result", "hit")
          .increment();
      return cached;
    }
    float[] vector = loader.apply(query);
    if (vector != null && vector.length > 0) {
      cache.put(key, vector);
    }
    meterRegistry
        .counter("ragforge.retrieval.query_embedding_cache", "result", "miss")
        .increment();
    return vector;
  }
}
