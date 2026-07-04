package com.ragforge.search.limit;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 基于 Redis ZSET 滑动窗口的分布式并发限流：全平台跨 pod 统一并发上限，取代进程内 Semaphore 的
 * "每 pod 各算一份、全局失控"。
 *
 * <p>ZSET 中 member=请求唯一 id、score=获取时间戳。每次获取先按租约清理过期 member（覆盖持有者
 * 崩溃未释放的场景），再判断当前并发数是否低于上限；清理+判断+写入用一段 Lua 保证原子。
 *
 * <p>Redis 异常时 <b>fail-open</b>（放行 + 计数告警）：检索并发限流是资源守卫而非安全闸，其可用性
 * 不应反过来拖垮检索；且检索线程池本身有界（core/max/queue）兜底。这与 API Key 限流的 fail-closed 口径不同。
 */
@Slf4j
public class RedisConcurrencyLimiter implements ConcurrencyLimiter {

  static final String KEY_PREFIX = "ragforge:retrieval:concurrency:";
  static final String FAIL_OPEN_METRIC = "ragforge.retrieval.limiter.error";

  // KEYS[1]=zset key；ARGV: 1=now(ms) 2=leaseMs 3=limit 4=member
  private static final String ACQUIRE_LUA =
      "redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, tonumber(ARGV[1]) - tonumber(ARGV[2]))\n"
          + "local count = redis.call('ZCARD', KEYS[1])\n"
          + "if count < tonumber(ARGV[3]) then\n"
          + "  redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4])\n"
          + "  redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]) * 2)\n"
          + "  return 1\n"
          + "else\n"
          + "  return 0\n"
          + "end";

  /** fail-open 时返回的空守卫：放行且释放无副作用。 */
  private static final Guard NOOP_GUARD = () -> {};

  private final StringRedisTemplate redisTemplate;
  private final MeterRegistry meterRegistry;
  private final DefaultRedisScript<Long> acquireScript;

  public RedisConcurrencyLimiter(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
    this.redisTemplate = redisTemplate;
    this.meterRegistry = meterRegistry;
    this.acquireScript = new DefaultRedisScript<>(ACQUIRE_LUA, Long.class);
  }

  @Override
  public Guard tryAcquire(String key, int limit, long leaseMs) {
    String redisKey = KEY_PREFIX + key;
    String member = UUID.randomUUID().toString();
    long now = System.currentTimeMillis();
    try {
      Long allowed =
          redisTemplate.execute(
              acquireScript,
              List.of(redisKey),
              Long.toString(now),
              Long.toString(leaseMs),
              Integer.toString(Math.max(1, limit)),
              member);
      if (allowed != null && allowed == 1L) {
        return new RedisGuard(redisKey, member);
      }
      return null; // 达到全局并发上限 → 快速失败
    } catch (RuntimeException e) {
      meterRegistry.counter(FAIL_OPEN_METRIC, "reason", "redis").increment();
      log.warn("分布式限流 Redis 异常，fail-open 放行 key={}: {}", key, e.getMessage());
      return NOOP_GUARD;
    }
  }

  private final class RedisGuard implements Guard {
    private final String redisKey;
    private final String member;
    private boolean released = false;

    RedisGuard(String redisKey, String member) {
      this.redisKey = redisKey;
      this.member = member;
    }

    @Override
    public void close() {
      if (released) {
        return;
      }
      released = true;
      try {
        redisTemplate.opsForZSet().remove(redisKey, member);
      } catch (RuntimeException e) {
        // 释放失败不致命：该 member 会随租约过期被下一次获取清理。
        log.warn("分布式限流释放异常 key={}: {}", redisKey, e.getMessage());
      }
    }
  }
}
