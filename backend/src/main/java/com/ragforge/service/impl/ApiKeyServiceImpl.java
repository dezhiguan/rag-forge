package com.ragforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ragforge.common.BizException;
import com.ragforge.mapper.ApiKeyMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.mapper.OrgMemberMapper;
import com.ragforge.model.dto.CreateApiKeyCommand;
import com.ragforge.model.entity.ApiKey;
import com.ragforge.model.entity.KnowledgeBase;
import com.ragforge.security.AdminOverrideHolder;
import com.ragforge.security.ApiKeyInterceptor;
import com.ragforge.security.OrgContextHolder;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import com.ragforge.service.ApiKeyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 开发者中心 API key：按当前组织归属。普通上下文管自己组织的 key；超管破玻璃=全平台只读治理(吊销)。 */
@Service
@RequiredArgsConstructor
public class ApiKeyServiceImpl implements ApiKeyService {

  private final ApiKeyMapper apiKeyMapper;
  private final ApiKeyInterceptor apiKeyInterceptor;
  private final OrgMemberMapper orgMemberMapper;
  private final KnowledgeBaseMapper knowledgeBaseMapper;

  /** 超管全平台视图（破玻璃）= 只读治理。 */
  public boolean isPlatformGovernance() {
    RagAuthContext ctx = RagAuthContextHolder.get();
    return ctx != null && ctx.isAdmin() && AdminOverrideHolder.isActive();
  }

  private Long currentUserId() {
    RagAuthContext ctx = RagAuthContextHolder.get();
    return ctx == null ? null : ctx.userId();
  }

  /** 当前组织 OWNER/ADMIN 才能管理 key（个人组织 owner 亦满足）。 */
  private void requireOrgAdmin(Long orgId) {
    Long uid = currentUserId();
    if (orgId == null || uid == null || !orgMemberMapper.isOrgAdmin(orgId, uid)) {
      throw new BizException(403, "NOT_ORG_ADMIN");
    }
  }

  @Override
  public List<ApiKey> listForCurrentOrg() {
    // 方案 B 定向治理：超管全平台视图下不再浏览所有 key（返回空），治理走 governanceSearch。
    Long orgId = OrgContextHolder.get();
    if (orgId == null) {
      return List.of();
    }
    return apiKeyMapper.selectList(
        new LambdaQueryWrapper<ApiKey>()
            .eq(ApiKey::getOrgId, orgId)
            .orderByDesc(ApiKey::getCreatedAt));
  }

  /** 定向治理查询（仅超管破玻璃）：按 key 名称或 key 前缀精确定位，不浏览全量。 */
  @Override
  public List<ApiKey> governanceSearch(String q) {
    if (!isPlatformGovernance()) {
      throw new BizException(403, "GOVERNANCE_REQUIRES_BREAKGLASS");
    }
    if (q == null || q.trim().length() < 3) {
      // 必须给出足够精确的检索词，避免变相浏览全量。
      throw new BizException(400, "GOVERNANCE_QUERY_TOO_SHORT");
    }
    String term = q.trim();
    return apiKeyMapper.selectList(
        new LambdaQueryWrapper<ApiKey>()
            .and(w -> w.like(ApiKey::getKeyName, term).or().likeRight(ApiKey::getKeyPrefix, term))
            .orderByDesc(ApiKey::getCreatedAt)
            .last("LIMIT 50"));
  }

  /** 定向吊销（仅超管破玻璃，强制原因 + 审计）。 */
  @Override
  @Transactional
  public ApiKey revokeWithReason(Long id, String reason) {
    if (!isPlatformGovernance()) {
      throw new BizException(403, "GOVERNANCE_REQUIRES_BREAKGLASS");
    }
    if (reason == null || reason.isBlank()) {
      throw new BizException(400, "REVOKE_REASON_REQUIRED");
    }
    ApiKey apiKey = apiKeyMapper.selectById(id);
    if (apiKey == null) {
      throw new BizException(404, "API_KEY_NOT_FOUND");
    }
    apiKeyMapper.update(null, new UpdateWrapper<ApiKey>().eq("id", id).set("enabled", false));
    apiKeyInterceptor.resetKeyCache();
    org.slf4j.LoggerFactory.getLogger(ApiKeyServiceImpl.class)
        .warn(
            "ragforge.audit api_key_breakglass_revoke keyId={} orgId={} byUser={} reason={}",
            id,
            apiKey.getOrgId(),
            currentUserId(),
            reason.trim());
    return apiKey;
  }

  @Override
  @Transactional
  public ApiKey create(CreateApiKeyCommand cmd) {
    // 超管全平台视图（破玻璃）为只读治理：不能创建 key，语义上不同于「非管理员」，单独给码。
    if (isPlatformGovernance()) {
      throw new BizException(403, "PLATFORM_VIEW_READONLY");
    }
    Long orgId = OrgContextHolder.get();
    requireOrgAdmin(orgId); // 平台视图(orgId=null)无法创建 → 须下钻组织

    String keyName = cmd == null ? null : cmd.keyName();
    if (keyName == null || keyName.isBlank()) {
      throw new BizException(400, "KEY_NAME_REQUIRED");
    }
    String scopeMode = normalizeScopeMode(cmd.scopeMode());
    // 本期仅 READ（READ_WRITE 暂不开放，无写接口接受 key）。
    String accessLevel = "READ";

    String allowedKbIdsJson = null;
    if ("KB_LIST".equals(scopeMode)) {
      List<Long> kbIds = cmd.allowedKbIds();
      if (kbIds == null || kbIds.isEmpty()) {
        throw new BizException(400, "ALLOWED_KB_IDS_REQUIRED");
      }
      validateKbsBelongToOrg(kbIds, orgId);
      allowedKbIdsJson = toJsonArray(kbIds);
    }

    String key = generateKey();
    ApiKey apiKey = new ApiKey();
    apiKey.setKeyName(keyName.trim());
    // 安全基线（M8-01/08）：明文不入库，仅存 hash + 展示前缀；鉴权按 hash 比对。
    apiKey.setKeyHash(sha256Hex(key));
    apiKey.setKeyPrefix(key.substring(0, Math.min(12, key.length())));
    apiKey.setEnabled(true);
    apiKey.setRateLimit(100);
    apiKey.setOrgId(orgId);
    apiKey.setScopeMode(scopeMode);
    apiKey.setAccessLevel(accessLevel);
    apiKey.setExpiresAt(cmd.expiresAt());
    if (allowedKbIdsJson != null) {
      apiKey.setAllowedKbIds(allowedKbIdsJson);
    }
    apiKey.setCreatedAt(LocalDateTime.now());
    apiKeyMapper.insert(apiKey);
    apiKeyInterceptor.resetKeyCache();
    // 明文仅通过本次创建响应返回一次（不落库、不落日志）。
    apiKey.setApiKey(key);
    return apiKey;
  }

  private String normalizeScopeMode(String raw) {
    String v = raw == null ? "" : raw.trim().toUpperCase();
    if ("KB_LIST".equals(v)) {
      return "KB_LIST";
    }
    return "ORG_ALL"; // 默认
  }

  /** KB_LIST 授权的库必须都存在且归属当前组织，否则拒绝（防越权注入他组织库）。 */
  private void validateKbsBelongToOrg(List<Long> kbIds, Long orgId) {
    Set<Long> distinct = new LinkedHashSet<>(kbIds);
    List<KnowledgeBase> kbs = knowledgeBaseMapper.selectBatchIds(distinct);
    if (kbs.size() != distinct.size()) {
      throw new BizException(404, "KB_NOT_FOUND");
    }
    for (KnowledgeBase kb : kbs) {
      if (kb.getOrgId() == null || !kb.getOrgId().equals(orgId)) {
        throw new BizException(403, "KB_NOT_IN_ORG");
      }
    }
  }

  private String toJsonArray(List<Long> ids) {
    return "["
        + new LinkedHashSet<>(ids).stream().map(String::valueOf).collect(Collectors.joining(","))
        + "]";
  }

  private String generateKey() {
    SecureRandom random = new SecureRandom();
    byte[] bytes = new byte[24];
    random.nextBytes(bytes);
    StringBuilder hex = new StringBuilder();
    for (byte b : bytes) {
      hex.append(String.format("%02x", b));
    }
    return "sk-rf-" + hex;
  }

  public static String sha256Hex(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(64);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new BizException(500, "HASH_ERROR");
    }
  }

  @Override
  @Transactional
  public ApiKey rename(Long id, String keyName) {
    ApiKey apiKey = apiKeyMapper.selectById(id);
    if (apiKey == null) {
      throw new BizException(404, "API_KEY_NOT_FOUND");
    }
    requireOrgAdmin(apiKey.getOrgId());
    if (keyName == null || keyName.isBlank()) {
      throw new BizException(400, "KEY_NAME_REQUIRED");
    }
    apiKey.setKeyName(keyName.trim());
    apiKeyMapper.update(
        null, new UpdateWrapper<ApiKey>().eq("id", id).set("key_name", keyName.trim()));
    return apiKey;
  }

  @Override
  @Transactional
  public ApiKey enable(Long id, boolean enabled) {
    ApiKey apiKey = apiKeyMapper.selectById(id);
    if (apiKey == null) {
      throw new BizException(404, "API_KEY_NOT_FOUND");
    }
    // 平台治理可吊销任意 key；普通上下文须为该 key 所属组织的 admin。
    if (!isPlatformGovernance()) {
      requireOrgAdmin(apiKey.getOrgId());
    }
    apiKey.setEnabled(enabled);
    apiKeyMapper.update(null, new UpdateWrapper<ApiKey>().eq("id", id).set("enabled", enabled));
    apiKeyInterceptor.resetKeyCache();
    return apiKey;
  }

  @Override
  @Transactional
  public void delete(Long id) {
    ApiKey apiKey = apiKeyMapper.selectById(id);
    if (apiKey == null) {
      throw new BizException(404, "API_KEY_NOT_FOUND");
    }
    // 删除是组织内操作（平台视图仅吊销不删，按设计）：破玻璃只读治理 → 单独给码，其余须为该 key 组织 admin。
    if (isPlatformGovernance()) {
      throw new BizException(403, "PLATFORM_VIEW_READONLY");
    }
    requireOrgAdmin(apiKey.getOrgId());
    apiKeyMapper.deleteById(id);
    apiKeyInterceptor.resetKeyCache();
  }
}
