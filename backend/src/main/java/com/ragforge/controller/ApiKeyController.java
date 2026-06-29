package com.ragforge.controller;

import com.ragforge.common.Result;
import com.ragforge.mapper.OrganizationMapper;
import com.ragforge.model.entity.ApiKey;
import com.ragforge.model.entity.Organization;
import com.ragforge.service.ApiKeyService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开发者中心 — API key（按当前组织作用域）。
 *
 * <p>不再是平台 ADMIN 专属：个人组织 owner / 团队 OWNER·ADMIN 管自己组织的 key；超管全平台视图(破玻璃)=
 * 只读治理（跨组织看、仅可吊销）。组织作用域与权限在 service 内强校验。
 */
@RestController
@RequestMapping("/api/v1/keys")
@RequiredArgsConstructor
public class ApiKeyController {

  private final ApiKeyService apiKeyService;
  private final OrganizationMapper organizationMapper;

  @GetMapping
  public Result<List<ApiKeyView>> list() {
    List<ApiKey> keys = apiKeyService.listForCurrentOrg();
    Map<Long, String> orgNames = loadOrgNames(keys);
    return Result.ok(keys.stream().map(k -> ApiKeyView.from(k, orgNames)).toList());
  }

  @PostMapping
  public Result<ApiKeyCreatedView> create(@RequestBody CreateApiKeyRequest req) {
    return Result.ok(ApiKeyCreatedView.from(apiKeyService.create(req.getKeyName())));
  }

  @PutMapping("/{id}/enable")
  public Result<ApiKeyView> enable(@PathVariable Long id, @RequestBody EnableRequest req) {
    ApiKey k = apiKeyService.enable(id, req.isEnabled());
    return Result.ok(ApiKeyView.from(k, loadOrgNames(List.of(k))));
  }

  @PatchMapping("/{id}")
  public Result<ApiKeyView> rename(@PathVariable Long id, @RequestBody CreateApiKeyRequest req) {
    ApiKey k = apiKeyService.rename(id, req.getKeyName());
    return Result.ok(ApiKeyView.from(k, loadOrgNames(List.of(k))));
  }

  @DeleteMapping("/{id}")
  public Result<Void> delete(@PathVariable Long id) {
    apiKeyService.delete(id);
    return Result.ok();
  }

  private Map<Long, String> loadOrgNames(List<ApiKey> keys) {
    List<Long> orgIds =
        keys.stream().map(ApiKey::getOrgId).filter(Objects::nonNull).distinct().toList();
    if (orgIds.isEmpty()) {
      return Map.of();
    }
    return organizationMapper.selectBatchIds(orgIds).stream()
        .collect(Collectors.toMap(Organization::getId, Organization::getName));
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
      String keyMasked,
      Boolean enabled,
      Integer rateLimit,
      Long orgId,
      String orgName,
      String scopes,
      String allowedKbIds,
      LocalDateTime lastUsedAt,
      LocalDateTime createdAt) {

    static ApiKeyView from(ApiKey k, Map<Long, String> orgNames) {
      return new ApiKeyView(
          k.getId(),
          k.getKeyName(),
          mask(k.getApiKey()),
          k.getEnabled(),
          k.getRateLimit(),
          k.getOrgId(),
          k.getOrgId() == null ? null : orgNames.get(k.getOrgId()),
          k.getScopes(),
          k.getAllowedKbIds(),
          k.getLastUsedAt(),
          k.getCreatedAt());
    }

    private static String mask(String key) {
      if (key == null || key.length() < 12) {
        return "****";
      }
      return key.substring(0, 8) + "****" + key.substring(key.length() - 4);
    }
  }

  public record ApiKeyCreatedView(
      Long id, String keyName, String apiKey, Boolean enabled, Long orgId, LocalDateTime createdAt) {

    static ApiKeyCreatedView from(ApiKey k) {
      return new ApiKeyCreatedView(
          k.getId(), k.getKeyName(), k.getApiKey(), k.getEnabled(), k.getOrgId(), k.getCreatedAt());
    }
  }
}
