package com.ragforge.controller;

import com.ragforge.common.Result;
import com.ragforge.model.dto.ChunkerAbRequest;
import com.ragforge.model.vo.ChunkerAbResponse;
import com.ragforge.service.ChunkerAbService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/evaluation")
@RequiredArgsConstructor
// 分块 A/B 仅读取 KB 文档做内存重分块评估、不改动线上 KB,权限对齐评测实验室其余端点
// (dataset/question/experiment/golden-set 均允许普通用户),避免前端可见但后端 403 的不一致。
@PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR','KB_VIEWER','USER','SERVICE_ACCOUNT')")
public class ChunkerAbController {

  private final ChunkerAbService chunkerAbService;

  @PostMapping("/chunker-ab")
  public Result<ChunkerAbResponse> run(@RequestBody ChunkerAbRequest request) {
    return Result.ok(chunkerAbService.run(request));
  }
}
