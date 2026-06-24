package com.ragforge.storage;

import com.ragforge.mapper.DocumentMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(AliyunOssStorage.class)
@ConditionalOnProperty(
    name = "ragforge.oss.orphan-cleanup.enabled",
    havingValue = "true",
    matchIfMissing = true)
@Slf4j
public class OssOrphanCleanupJob {

  static final String CLEANED_METRIC = "ragforge.oss.orphan.cleaned";
  private static final Duration ORPHAN_GRACE_PERIOD = Duration.ofHours(24);

  private final AliyunOssStorage storage;
  private final StorageProperties storageProperties;
  private final DocumentMapper documentMapper;
  private final Counter cleanedCounter;
  private final Clock clock;

  public OssOrphanCleanupJob(
      AliyunOssStorage storage,
      StorageProperties storageProperties,
      DocumentMapper documentMapper,
      MeterRegistry meterRegistry) {
    this(storage, storageProperties, documentMapper, meterRegistry, Clock.systemUTC());
  }

  OssOrphanCleanupJob(
      AliyunOssStorage storage,
      StorageProperties storageProperties,
      DocumentMapper documentMapper,
      MeterRegistry meterRegistry,
      Clock clock) {
    this.storage = storage;
    this.storageProperties = storageProperties;
    this.documentMapper = documentMapper;
    this.cleanedCounter = meterRegistry.counter(CLEANED_METRIC);
    this.clock = clock;
  }

  @Scheduled(cron = "${ragforge.oss.orphan-cleanup.cron:0 0 4 * * *}")
  @SchedulerLock(name = "oss-orphan-cleanup", lockAtMostFor = "PT1H")
  public void cleanup() {
    String bucket = storageProperties.getAliyun().getBucket();
    if (bucket == null || bucket.isBlank()) {
      log.debug("Skip OSS orphan cleanup because storage.aliyun.bucket is empty");
      return;
    }

    Instant deleteBefore = Instant.now(clock).minus(ORPHAN_GRACE_PERIOD);
    int cleaned = 0;
    for (AliyunOssStorage.OssObjectInfo object : storage.listObjects(bucket, "")) {
      if (!isUploadObject(object.key())) {
        continue;
      }
      if (object.lastModified() == null || !object.lastModified().isBefore(deleteBefore)) {
        continue;
      }
      if (documentMapper.countByStorageLocation(bucket, object.key()) > 0) {
        continue;
      }
      if (safeDelete(bucket, object.key())) {
        cleaned += 1;
        cleanedCounter.increment();
      }
    }
    log.info("OSS orphan cleanup finished: bucket={}, cleaned={}", bucket, cleaned);
  }

  private boolean safeDelete(String bucket, String key) {
    try {
      storage.delete(bucket, key);
      return true;
    } catch (Exception e) {
      log.warn("Failed to delete OSS orphan object: bucket={}, key={}", bucket, key, e);
      return false;
    }
  }

  private boolean isUploadObject(String key) {
    return key != null && key.contains("/kb_") && key.contains("/uplt_");
  }
}
