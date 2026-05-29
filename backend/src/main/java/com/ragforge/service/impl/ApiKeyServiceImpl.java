package com.ragforge.service.impl;

import com.ragforge.common.BizException;
import com.ragforge.mapper.ApiKeyMapper;
import com.ragforge.model.entity.ApiKey;
import com.ragforge.service.ApiKeyService;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApiKeyServiceImpl implements ApiKeyService {

  private final ApiKeyMapper apiKeyMapper;

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
    String key = "sk-rf-" + HexFormat.of().formatHex(bytes);

    ApiKey apiKey = new ApiKey();
    apiKey.setKeyName(keyName);
    apiKey.setApiKey(key);
    apiKey.setEnabled(true);
    apiKey.setRateLimit(100);
    apiKey.setCreatedAt(LocalDateTime.now());
    apiKeyMapper.insert(apiKey);
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
  }
}
