package com.ragforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ragforge.common.BizException;
import com.ragforge.mapper.ApiKeyMapper;
import com.ragforge.mapper.OrgMemberMapper;
import com.ragforge.model.entity.ApiKey;
import com.ragforge.security.AdminOverrideHolder;
import com.ragforge.security.ApiKeyInterceptor;
import com.ragforge.security.OrgContextHolder;
import com.ragforge.security.RagAuthContext;
import com.ragforge.security.RagAuthContextHolder;
import com.ragforge.service.ApiKeyService;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
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
            .and(w -> w.like(ApiKey::getKeyName, term).or().likeRight(ApiKey::getApiKey, term))
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
      throw new BizException(404, "API Key 不存在");
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
  public ApiKey create(String keyName) {
    Long orgId = OrgContextHolder.get();
    requireOrgAdmin(orgId); // 平台视图(orgId=null)无法创建 → 须下钻组织
    SecureRandom random = new SecureRandom();
    byte[] bytes = new byte[24];
    random.nextBytes(bytes);
    StringBuilder hex = new StringBuilder();
    for (byte b : bytes) {
      hex.append(String.format("%02x", b));
    }
    String key = "sk-rf-" + hex;

    ApiKey apiKey = new ApiKey();
    apiKey.setKeyName(keyName);
    apiKey.setApiKey(key);
    apiKey.setEnabled(true);
    apiKey.setRateLimit(100);
    apiKey.setOrgId(orgId);
    apiKey.setCreatedAt(LocalDateTime.now());
    apiKeyMapper.insert(apiKey);
    apiKeyInterceptor.resetKeyCache();
    return apiKey;
  }

  @Override
  @Transactional
  public ApiKey rename(Long id, String keyName) {
    ApiKey apiKey = apiKeyMapper.selectById(id);
    if (apiKey == null) {
      throw new BizException(404, "API Key 不存在");
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
      throw new BizException(404, "API Key 不存在");
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
      throw new BizException(404, "API Key 不存在");
    }
    // 删除是组织内操作（平台视图仅吊销不删，按设计），须为该 key 所属组织的 admin。
    requireOrgAdmin(apiKey.getOrgId());
    apiKeyMapper.deleteById(id);
    apiKeyInterceptor.resetKeyCache();
  }
}
