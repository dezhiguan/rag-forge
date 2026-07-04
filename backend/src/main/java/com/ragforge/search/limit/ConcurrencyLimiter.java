package com.ragforge.search.limit;

/**
 * 检索并发限流抽象。两种实现：进程内 {@link LocalConcurrencyLimiter}（每 pod 本地）与分布式
 * {@link RedisConcurrencyLimiter}（全局），由 {@link RetrievalConcurrencyConfig} 按配置择一。
 */
public interface ConcurrencyLimiter {

  /**
   * 尝试为 key 获取一个并发许可；短暂等待后仍拿不到则返回 {@code null}，调用方据此快速失败(429)。
   *
   * @param key 限流维度（检索策略名）
   * @param limit 该维度允许的最大并发
   * @param leaseMs 许可租约毫秒；分布式实现用它回收持有者崩溃后泄漏的许可，应大于单次请求最长耗时
   * @return 成功返回可释放的 {@link Guard}；被限流返回 {@code null}
   */
  Guard tryAcquire(String key, int limit, long leaseMs);

  /** 已获取的并发许可，{@link #close()} 释放；可安全用于 try-with-resources。 */
  interface Guard extends AutoCloseable {
    @Override
    void close();
  }
}
