package com.ragforge.search.limit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ragforge.search.limit.ConcurrencyLimiter.Guard;
import org.junit.jupiter.api.Test;

class LocalConcurrencyLimiterTest {

  @Test
  void acquiresUpToLimitThenRejects() {
    LocalConcurrencyLimiter limiter = new LocalConcurrencyLimiter();

    Guard g1 = limiter.tryAcquire("vector", 2, 1000);
    Guard g2 = limiter.tryAcquire("vector", 2, 1000);
    assertThat(g1).isNotNull();
    assertThat(g2).isNotNull();

    // 达到上限 → 快速失败返回 null
    assertThat(limiter.tryAcquire("vector", 2, 1000)).isNull();

    // 释放一个许可后可再获取
    g1.close();
    Guard g4 = limiter.tryAcquire("vector", 2, 1000);
    assertThat(g4).isNotNull();

    g2.close();
    g4.close();
  }

  @Test
  void differentKeysAreIndependent() {
    LocalConcurrencyLimiter limiter = new LocalConcurrencyLimiter();

    Guard a = limiter.tryAcquire("keyword", 1, 1000);
    Guard b = limiter.tryAcquire("full", 1, 1000);
    assertThat(a).isNotNull();
    assertThat(b).isNotNull();

    // keyword 维度已满，但 full 维度不受影响
    assertThat(limiter.tryAcquire("keyword", 1, 1000)).isNull();

    a.close();
    b.close();
  }

  @Test
  void limitBelowOneTreatedAsOne() {
    LocalConcurrencyLimiter limiter = new LocalConcurrencyLimiter();

    Guard g = limiter.tryAcquire("x", 0, 1000);
    assertThat(g).isNotNull();
    assertThat(limiter.tryAcquire("x", 0, 1000)).isNull();
    g.close();
  }

  @Test
  void closeIsIdempotent() {
    LocalConcurrencyLimiter limiter = new LocalConcurrencyLimiter();

    Guard g = limiter.tryAcquire("y", 1, 1000);
    assertThat(g).isNotNull();
    g.close();
    g.close(); // 重复关闭不应重复释放（否则会把并发上限撑大）

    Guard g2 = limiter.tryAcquire("y", 1, 1000);
    Guard g3 = limiter.tryAcquire("y", 1, 1000);
    assertThat(g2).isNotNull();
    assertThat(g3).isNull(); // 若 close 非幂等，这里会错误地拿到第二个许可
    g2.close();
  }
}
