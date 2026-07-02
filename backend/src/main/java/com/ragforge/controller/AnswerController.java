package com.ragforge.controller;

import com.ragforge.answer.AnswerModels.AnswerRequest;
import com.ragforge.answer.AnswerService;
import com.ragforge.common.BizException;
import com.ragforge.security.KbAccessGuard;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AnswerController {

  private final AnswerService answerService;
  private final KbAccessGuard kbAccessGuard;

  @PostMapping(value = "/answer", produces = "text/event-stream;charset=UTF-8")
  // 与 /search 一致纳入 USER:普通用户(如个人组织 owner)也应能对自己有权的库应答;
  // 真正的逐库归属校验由下方 kbAccessGuard.filterReadable 完成,补 USER 不放宽 KB 访问。
  @PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR','KB_VIEWER','USER','SERVICE_ACCOUNT')")
  public SseEmitter answer(@RequestBody AnswerRequest request, HttpServletResponse response) {
    if (request == null || request.getKbIds() == null || request.getKbIds().isEmpty()) {
      throw new BizException(400, "KB_IDS_REQUIRED");
    }
    Set<Long> readableKbIds = kbAccessGuard.filterReadable(request.getKbIds());
    if (readableKbIds.isEmpty()) {
      throw new BizException(403, "KB_ACCESS_DENIED");
    }
    request.setKbIds(new ArrayList<>(readableKbIds));
    answerService.validateAnswerMode(request);
    // 防御性 SSE 头：即使上游代理（Nginx / ALB / Ingress）漏配 buffering，也能让支持
    // X-Accel-Buffering 的代理立刻 flush 每个 chunk；no-cache 阻止任何中间缓存。
    response.setHeader("X-Accel-Buffering", "no");
    response.setHeader("Cache-Control", "no-cache");
    return answerService.answer(request);
  }
}
