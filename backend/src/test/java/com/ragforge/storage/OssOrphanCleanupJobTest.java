package com.ragforge.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectMetadata;
import com.ragforge.mapper.DocumentMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OssOrphanCleanupJobTest {

  private static final String BUCKET = "rag-raw-docs";
  private static final Instant NOW = Instant.parse("2026-06-24T00:00:00Z");

  @Test
  void cleanup_keepsRegisteredAndRecentObjects_andDeletesOldOrphans() {
    FakeOssGateway gateway = new FakeOssGateway();
    String registered = "tenant-a/kb_1/uplt_registered/a.md";
    String recent = "tenant-a/kb_1/uplt_recent/b.md";
    String orphan = "tenant-a/kb_1/uplt_orphan/c.md";
    gateway.add(registered, NOW.minus(Duration.ofHours(26)));
    gateway.add(recent, NOW.minus(Duration.ofHours(2)));
    gateway.add(orphan, NOW.minus(Duration.ofHours(25)));

    AliyunOssStorage storage = new AliyunOssStorage(gateway, BUCKET, true);
    StorageProperties properties = new StorageProperties();
    properties.getAliyun().setBucket(BUCKET);
    DocumentMapper documentMapper = Mockito.mock(DocumentMapper.class);
    when(documentMapper.countByStorageLocation(BUCKET, registered)).thenReturn(1L);
    when(documentMapper.countByStorageLocation(BUCKET, orphan)).thenReturn(0L);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    OssOrphanCleanupJob job =
        new OssOrphanCleanupJob(
            storage,
            properties,
            documentMapper,
            meterRegistry,
            Clock.fixed(NOW, ZoneOffset.UTC));

    job.cleanup();

    assertThat(gateway.deleted).containsExactly(orphan);
    assertThat(gateway.objects).contains(registered, recent);
    assertThat(meterRegistry.counter(OssOrphanCleanupJob.CLEANED_METRIC).count()).isEqualTo(1.0);
  }

  private static final class FakeOssGateway implements AliyunOssStorage.OssGateway {
    private final List<OSSObjectSummary> summaries = new ArrayList<>();
    private final Set<String> objects = new HashSet<>();
    private final List<String> deleted = new ArrayList<>();

    private void add(String key, Instant lastModified) {
      OSSObjectSummary summary = new OSSObjectSummary();
      summary.setBucketName(BUCKET);
      summary.setKey(key);
      summary.setLastModified(java.util.Date.from(lastModified));
      summary.setSize(12L);
      summaries.add(summary);
      objects.add(key);
    }

    @Override
    public boolean bucketExists(String bucket) {
      return true;
    }

    @Override
    public String put(String bucket, String key, InputStream in, ObjectMetadata meta) {
      objects.add(key);
      return "etag-" + key;
    }

    @Override
    public InputStream get(String bucket, String key) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ObjectMetadata head(String bucket, String key) {
      return null;
    }

    @Override
    public String presigned(
        String bucket, String key, HttpMethod method, Duration ttl, ObjectMetadata meta) {
      return "https://oss.example/" + bucket + "/" + key;
    }

    @Override
    public List<OSSObjectSummary> list(String bucket, String prefix) {
      return summaries.stream()
          .filter((summary) -> summary.getKey().startsWith(prefix))
          .toList();
    }

    @Override
    public void delete(String bucket, String key) {
      objects.remove(key);
      deleted.add(key);
    }
  }
}
