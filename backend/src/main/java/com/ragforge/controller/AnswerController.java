package com.ragforge.controller;

import com.ragforge.answer.AnswerModels.AnswerRequest;
import com.ragforge.answer.AnswerService;
import com.ragforge.common.BizException;
import com.ragforge.security.KbAccessGuard;
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

  @PostMapping("/answer")
  @PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR','KB_VIEWER','SERVICE_ACCOUNT')")
  public SseEmitter answer(@RequestBody AnswerRequest request) {
    if (request == null || request.getKbIds() == null || request.getKbIds().isEmpty()) {
      throw new BizException(400, "KB_IDS_REQUIRED");
    }
    Set<Long> readableKbIds = kbAccessGuard.filterReadable(request.getKbIds());
    if (readableKbIds.isEmpty()) {
      throw new BizException(403, "KB_ACCESS_DENIED");
    }
    request.setKbIds(new ArrayList<>(readableKbIds));
    answerService.validateAnswerMode(request);
    return answerService.answer(request);
  }
}
