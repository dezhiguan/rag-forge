package com.ragforge.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.mapper.ApiKeyMapper;
import com.ragforge.model.entity.ApiKey;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitRunner implements ApplicationRunner {

  private static final String DEV_KEY_NAME = "dev";
  private static final String DEV_API_KEY = "sk-ragforge-dev";

  private final ApiKeyMapper apiKeyMapper;

  @Override
  public void run(ApplicationArguments args) {
    Long count =
        apiKeyMapper.selectCount(
            new LambdaQueryWrapper<ApiKey>().eq(ApiKey::getApiKey, DEV_API_KEY));
    if (count != null && count > 0) {
      return;
    }

    ApiKey apiKey = new ApiKey();
    apiKey.setKeyName(DEV_KEY_NAME);
    apiKey.setApiKey(DEV_API_KEY);
    apiKey.setEnabled(true);
    apiKey.setRateLimit(100);
    apiKey.setCreatedAt(LocalDateTime.now());
    apiKeyMapper.insert(apiKey);
    log.info("Seeded dev API key: {}", DEV_KEY_NAME);
  }
}
