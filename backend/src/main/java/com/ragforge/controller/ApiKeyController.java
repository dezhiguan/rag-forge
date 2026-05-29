package com.ragforge.controller;

import com.ragforge.common.Result;
import com.ragforge.model.entity.ApiKey;
import com.ragforge.service.ApiKeyService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/api-keys")
@RequiredArgsConstructor
public class ApiKeyController {

  private final ApiKeyService apiKeyService;

  @GetMapping
  public Result<List<ApiKey>> listAll() {
    return Result.ok(apiKeyService.listAll());
  }

  @PostMapping
  public Result<ApiKey> create(@RequestBody CreateApiKeyRequest req) {
    return Result.ok(apiKeyService.create(req.getKeyName()));
  }

  @PutMapping("/{id}/enable")
  public Result<ApiKey> enable(@PathVariable Long id, @RequestBody EnableRequest req) {
    return Result.ok(apiKeyService.enable(id, req.isEnabled()));
  }

  @DeleteMapping("/{id}")
  public Result<Void> delete(@PathVariable Long id) {
    apiKeyService.delete(id);
    return Result.ok();
  }

  // ---- DTOs ----

  @lombok.Data
  public static class CreateApiKeyRequest {
    private String keyName;
  }

  @lombok.Data
  public static class EnableRequest {
    private boolean enabled;
  }
}
