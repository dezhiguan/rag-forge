package com.ragforge.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class QueryEmbeddingCacheTest {

  private QueryEmbeddingCache cache(boolean enabled) {
    return new QueryEmbeddingCache(enabled, 100, 60, new SimpleMeterRegistry());
  }

  @Test
  void secondIdenticalQueryHitsCacheAndSkipsLoader() {
    QueryEmbeddingCache c = cache(true);
    AtomicInteger loads = new AtomicInteger();
    java.util.function.Function<String, float[]> loader =
        q -> {
          loads.incrementAndGet();
          return new float[] {1f, 2f, 3f};
        };

    float[] first = c.get("向量检索", loader);
    float[] second = c.get("向量检索", loader);

    assertThat(first).containsExactly(1f, 2f, 3f);
    assertThat(second).containsExactly(1f, 2f, 3f);
    assertThat(loads.get()).isEqualTo(1); // 第二次命中缓存，未再调用 loader
  }

  @Test
  void normalizesWhitespaceSoTrimmedQueriesShareCache() {
    QueryEmbeddingCache c = cache(true);
    AtomicInteger loads = new AtomicInteger();
    java.util.function.Function<String, float[]> loader =
        q -> {
          loads.incrementAndGet();
          return new float[] {9f};
        };

    c.get("  redis 持久化  ", loader);
    c.get("redis 持久化", loader);

    assertThat(loads.get()).isEqualTo(1);
  }

  @Test
  void disabledCacheAlwaysInvokesLoader() {
    QueryEmbeddingCache c = cache(false);
    AtomicInteger loads = new AtomicInteger();
    java.util.function.Function<String, float[]> loader =
        q -> {
          loads.incrementAndGet();
          return new float[] {1f};
        };

    c.get("q", loader);
    c.get("q", loader);

    assertThat(loads.get()).isEqualTo(2); // 禁用时不缓存
  }

  @Test
  void blankQueryBypassesCache() {
    QueryEmbeddingCache c = cache(true);
    AtomicInteger loads = new AtomicInteger();
    c.get("  ", q -> {
      loads.incrementAndGet();
      return new float[] {0f};
    });
    assertThat(loads.get()).isEqualTo(1);
  }
}
