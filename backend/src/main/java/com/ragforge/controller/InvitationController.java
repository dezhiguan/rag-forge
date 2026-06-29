package com.ragforge.controller;

import com.ragforge.common.Result;
import com.ragforge.service.InvitationService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 被邀请方视角：查看/接受/拒绝我的组织邀请。 */
@RestController
@RequestMapping("/api/v1/invitations")
@RequiredArgsConstructor
public class InvitationController {

  private final InvitationService invitationService;

  @GetMapping("/mine")
  public Result<List<Map<String, Object>>> mine() {
    return Result.ok(invitationService.myPending());
  }

  @PostMapping("/{invitationId}/accept")
  public Result<Void> accept(@PathVariable Long invitationId) {
    invitationService.accept(invitationId);
    return Result.ok();
  }

  @PostMapping("/{invitationId}/decline")
  public Result<Void> decline(@PathVariable Long invitationId) {
    invitationService.decline(invitationId);
    return Result.ok();
  }
}
