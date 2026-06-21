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
@PreAuthorize("hasAnyRole('ADMIN','KB_EDITOR')")
public class ChunkerAbController {

  private final ChunkerAbService chunkerAbService;

  @PostMapping("/chunker-ab")
  public Result<ChunkerAbResponse> run(@RequestBody ChunkerAbRequest request) {
    return Result.ok(chunkerAbService.run(request));
  }
}
