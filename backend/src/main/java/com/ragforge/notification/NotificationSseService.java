package com.ragforge.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.common.BizException;
import com.ragforge.mapper.NotificationMapper;
import com.ragforge.model.entity.Notification;
import com.ragforge.security.JwtVerifier;
import com.ragforge.security.RagAuthContext;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 订阅服务：浏览器原生 EventSource 无法设置自定义 header，故用 query param 的 token 鉴权。 建连后立即下发一次当前未读数做对齐，之后由
 * {@link NotificationPusher} 增量推送。
 */
@Service
@RequiredArgsConstructor
public class NotificationSseService {

  private static final Logger log = LoggerFactory.getLogger(NotificationSseService.class);

  /** SseEmitter 服务端超时 30min；到期正常结束，由前端 EventSource 自动重连兜底。 */
  static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

  private final JwtVerifier jwtVerifier;
  private final SseEmitterRegistry registry;
  private final NotificationMapper notificationMapper;

  public SseEmitter subscribe(String token) {
    Long userId = authenticate(token);
    SseEmitter emitter = registry.register(userId, EMITTER_TIMEOUT_MS);
    sendInitialUnread(emitter, userId);
    return emitter;
  }

  private Long authenticate(String token) {
    if (!StringUtils.hasText(token)) {
      throw new BizException(401, "UNAUTHORIZED");
    }
    try {
      RagAuthContext ctx = jwtVerifier.toContext(jwtVerifier.verify(token));
      if (ctx == null || ctx.userId() == null) {
        throw new BizException(401, "UNAUTHORIZED");
      }
      return ctx.userId();
    } catch (BizException e) {
      throw e;
    } catch (Exception e) {
      throw new BizException(401, "UNAUTHORIZED");
    }
  }

  private void sendInitialUnread(SseEmitter emitter, Long userId) {
    Long c =
        notificationMapper.selectCount(
            new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .isNull(Notification::getReadAt));
    long count = c == null ? 0L : c;
    try {
      emitter.send(SseEmitter.event().name("unread").data(count));
    } catch (IOException ex) {
      emitter.completeWithError(ex);
    }
  }
}
