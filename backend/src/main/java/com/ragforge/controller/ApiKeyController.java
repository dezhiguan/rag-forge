package com.ragforge.controller;

import com.ragforge.common.Result;
import com.ragforge.model.entity.ApiKey;
import com.ragforge.service.ApiKeyService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
@PreAuthorize("hasRole('ADMIN')")
public class ApiKeyController {

  private final ApiKeyService apiKeyService;

  @GetMapping
  public Result<List<ApiKeyView>> listAll() {
    return Result.ok(apiKeyService.listAll().stream().map(ApiKeyView::from).toList());
  }

  @PostMapping
  public Result<ApiKeyCreatedView> create(@RequestBody CreateApiKeyRequest req) {
    return Result.ok(ApiKeyCreatedView.from(apiKeyService.create(req.getKeyName())));
  }

  @PutMapping("/{id}/enable")
  public Result<ApiKeyView> enable(@PathVariable Long id, @RequestBody EnableRequest req) {
    return Result.ok(ApiKeyView.from(apiKeyService.enable(id, req.isEnabled())));
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

  public record ApiKeyView(
      Long id,
      String keyName,
      Boolean enabled,
      Integer rateLimit,
      String principalType,
      String principalId,
      String scopes,
      String allowedKbIds,
      LocalDateTime createdAt) {

    static ApiKeyView from(ApiKey apiKey) {
      return new ApiKeyView(
          apiKey.getId(),
          apiKey.getKeyName(),
          apiKey.getEnabled(),
          apiKey.getRateLimit(),
          apiKey.getPrincipalType(),
          apiKey.getPrincipalId(),
          apiKey.getScopes(),
          apiKey.getAllowedKbIds(),
          apiKey.getCreatedAt());
    }
  }

  public record ApiKeyCreatedView(
      Long id,
      String keyName,
      String apiKey,
      Boolean enabled,
      Integer rateLimit,
      String principalType,
      String principalId,
      String scopes,
      String allowedKbIds,
      LocalDateTime createdAt) {

    static ApiKeyCreatedView from(ApiKey apiKey) {
      return new ApiKeyCreatedView(
          apiKey.getId(),
          apiKey.getKeyName(),
          apiKey.getApiKey(),
          apiKey.getEnabled(),
          apiKey.getRateLimit(),
          apiKey.getPrincipalType(),
          apiKey.getPrincipalId(),
          apiKey.getScopes(),
          apiKey.getAllowedKbIds(),
          apiKey.getCreatedAt());
    }
  }
}
