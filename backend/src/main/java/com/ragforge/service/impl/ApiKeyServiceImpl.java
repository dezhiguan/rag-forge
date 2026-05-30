package com.ragforge.service.impl;

import com.ragforge.common.BizException;
import com.ragforge.config.ApiKeyInterceptor;
import com.ragforge.mapper.ApiKeyMapper;
import com.ragforge.model.entity.ApiKey;
import com.ragforge.service.ApiKeyService;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApiKeyServiceImpl implements ApiKeyService {

  private final ApiKeyMapper apiKeyMapper;
  private final ApiKeyInterceptor apiKeyInterceptor;

  @Override
  public List<ApiKey> listAll() {
    return apiKeyMapper.selectList(null);
  }

  @Override
  @Transactional
  public ApiKey create(String keyName) {
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
    apiKey.setCreatedAt(LocalDateTime.now());
    apiKeyMapper.insert(apiKey);
    apiKeyInterceptor.resetKeyCache();
    return apiKey;
  }

  @Override
  @Transactional
  public ApiKey enable(Long id, boolean enabled) {
    ApiKey apiKey = apiKeyMapper.selectById(id);
    if (apiKey == null) {
      throw new BizException(404, "API Key 不存在");
    }
    apiKey.setEnabled(enabled);
    apiKeyMapper.updateById(apiKey);
    return apiKey;
  }

  @Override
  @Transactional
  public void delete(Long id) {
    ApiKey apiKey = apiKeyMapper.selectById(id);
    if (apiKey == null) {
      throw new BizException(404, "API Key 不存在");
    }
    apiKeyMapper.deleteById(id);
    apiKeyInterceptor.resetKeyCache();
  }
}
