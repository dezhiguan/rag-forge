package com.ragforge.notification;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 站内通知 SSE 连接登记表：按 userId 维持连接（一个用户多标签页 → 多 emitter）。 负责推送未读数、心跳保活，并在连接结束/超时/出错时清理，防止内存泄漏。
 */
@Component
public class SseEmitterRegistry {

  private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

  /** 心跳间隔 25s，须小于 Nginx proxy_read_timeout（300s），避免入口层掐断长连接。 */
  private static final long HEARTBEAT_INTERVAL_MS = 25_000L;

  private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

  /** 建立一条 SSE 连接并登记；到期/结束/出错自动摘除。 */
  public SseEmitter register(Long userId, long timeoutMs) {
    SseEmitter emitter = new SseEmitter(timeoutMs);
    bind(userId, emitter);
    return emitter;
  }

  /** 绑定 emitter 与生命周期回调（包级可见，便于单测注入 mock emitter）。 */
  void bind(Long userId, SseEmitter emitter) {
    emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);
    emitter.onCompletion(() -> remove(userId, emitter));
    emitter.onTimeout(
        () -> {
          emitter.complete();
          remove(userId, emitter);
        });
    emitter.onError(e -> remove(userId, emitter));
  }

  /** 向该用户所有本地连接推送最新未读数；发送失败的连接就地摘除。 */
  public void sendUnread(Long userId, long count) {
    List<SseEmitter> list = emitters.get(userId);
    if (list == null || list.isEmpty()) {
      return;
    }
    for (SseEmitter emitter : list) {
      try {
        emitter.send(SseEmitter.event().name("unread").data(count));
      } catch (Exception ex) {
        remove(userId, emitter);
      }
    }
  }

  /** 定时心跳：给所有连接发注释帧保活，断开的连接摘除。 */
  @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MS)
  public void heartbeat() {
    emitters.forEach(
        (userId, list) -> {
          for (SseEmitter emitter : list) {
            try {
              emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception ex) {
              remove(userId, emitter);
            }
          }
        });
  }

  private void remove(Long userId, SseEmitter emitter) {
    List<SseEmitter> list = emitters.get(userId);
    if (list == null) {
      return;
    }
    list.remove(emitter);
    if (list.isEmpty()) {
      emitters.remove(userId);
    }
  }

  /** 某用户当前本地连接数（测试/监控用）。 */
  public int connectionCount(Long userId) {
    List<SseEmitter> list = emitters.get(userId);
    return list == null ? 0 : list.size();
  }

  /** 当前有在线连接的用户数（测试/监控用）。 */
  public int onlineUserCount() {
    return emitters.size();
  }
}
