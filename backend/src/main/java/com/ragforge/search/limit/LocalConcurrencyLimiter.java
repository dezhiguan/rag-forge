package com.ragforge.search.limit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 进程内 {@link Semaphore} 并发限流（每 pod 本地）。为分布式限流关闭或作为回退时使用，保持与历史一致的行为。
 * 每个 key 首次出现时按 {@code limit} 建立一个信号量并缓存（配置固定，运行时不重建）。
 */
public class LocalConcurrencyLimiter implements ConcurrencyLimiter {

  /** 获取许可的最长等待，超时即快速失败，避免请求线程长时间阻塞。 */
  static final long ACQUIRE_WAIT_MS = 200;

  private final ConcurrentHashMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();

  @Override
  public Guard tryAcquire(String key, int limit, long leaseMs) {
    Semaphore semaphore = semaphores.computeIfAbsent(key, k -> new Semaphore(Math.max(1, limit)));
    boolean acquired;
    try {
      acquired = semaphore.tryAcquire(ACQUIRE_WAIT_MS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
    if (!acquired) {
      return null;
    }
    return new LocalGuard(semaphore);
  }

  private static final class LocalGuard implements Guard {
    private final Semaphore semaphore;
    private boolean released = false;

    LocalGuard(Semaphore semaphore) {
      this.semaphore = semaphore;
    }

    @Override
    public void close() {
      if (!released) {
        released = true;
        semaphore.release();
      }
    }
  }
}
