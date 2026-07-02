package com.ragforge.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.mapper.ApiKeyMapper;
import com.ragforge.model.entity.ApiKey;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DataInitRunner implements ApplicationRunner {

  private static final String DEV_KEY_NAME = "dev";
  private static final String DEV_API_KEY = "sk-ragforge-dev";

  private final ApiKeyMapper apiKeyMapper;

  @Override
  public void run(ApplicationArguments args) {
    // 安全基线（M8-01/08）：种子 key 同样只存 hash + 前缀，不落明文。
    String keyHash = com.ragforge.service.impl.ApiKeyServiceImpl.sha256Hex(DEV_API_KEY);
    Long count =
        apiKeyMapper.selectCount(new LambdaQueryWrapper<ApiKey>().eq(ApiKey::getKeyHash, keyHash));
    if (count != null && count > 0) {
      return;
    }

    ApiKey apiKey = new ApiKey();
    apiKey.setKeyName(DEV_KEY_NAME);
    apiKey.setKeyHash(keyHash);
    apiKey.setKeyPrefix(DEV_API_KEY.substring(0, Math.min(12, DEV_API_KEY.length())));
    apiKey.setEnabled(true);
    apiKey.setRateLimit(100);
    apiKey.setCreatedAt(LocalDateTime.now());
    apiKeyMapper.insert(apiKey);
    log.info("Seeded dev API key: {}", DEV_KEY_NAME);
  }
}
