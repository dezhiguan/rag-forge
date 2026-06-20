package com.ragforge.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {

  @Bean
  @ConditionalOnProperty(prefix = "storage", name = "backend", havingValue = "aliyun")
  ObjectStorage aliyunObjectStorage(StorageProperties properties) {
    return new AliyunOssStorage(properties);
  }

  @Bean
  @ConditionalOnProperty(prefix = "storage", name = "backend", havingValue = "local")
  ObjectStorage localObjectStorage(
      StorageProperties properties,
      @Value("${app.file.storage-path:${ragforge.files.upload-path:./data/files}}")
          String legacyRootPath) {
    String rootPath = properties.getLocal().getRootPath();
    if (rootPath == null || rootPath.isBlank()) {
      rootPath = legacyRootPath;
    }
    return new LocalDiskStorage(rootPath);
  }
}
