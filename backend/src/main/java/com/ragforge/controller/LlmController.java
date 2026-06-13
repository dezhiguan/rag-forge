package com.ragforge.controller;

import com.ragforge.common.Result;
import com.ragforge.model.dto.LlmGenerateRequest;
import com.ragforge.service.LlmService;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class LlmController {

  private final LlmService llmService;

  public LlmController(LlmService llmService) {
    this.llmService = llmService;
  }

  @PostMapping("/llm/generate")
  public Result<Map<String, Object>> generate(@RequestBody LlmGenerateRequest request) {
    return Result.ok(llmService.generate(request));
  }
}
