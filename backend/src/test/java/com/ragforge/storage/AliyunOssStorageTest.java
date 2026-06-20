package com.ragforge.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.model.ObjectMetadata;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AliyunOssStorageTest {

  @Test
  void constructor_checksBucketConnectivity() {
    FakeOssGateway gateway = new FakeOssGateway();
    gateway.bucketExists = false;

    assertThatThrownBy(() -> new AliyunOssStorage(gateway, "ragforge-dev", true))
        .isInstanceOf(StorageException.class)
        .hasMessageContaining("bucket");
  }

  @Test
  void put_writesObjectAndReturnsResult() {
    FakeOssGateway gateway = new FakeOssGateway();
    AliyunOssStorage storage = new AliyunOssStorage(gateway, "ragforge-dev", true);
    ObjectMeta meta =
        new ObjectMeta("application/pdf", 5L, null, Map.of("source", "unit-test"));

    PutResult result =
        storage.put("ragforge-dev", "docs/a.pdf", bytes("hello"), meta);

    assertThat(result.getBucket()).isEqualTo("ragforge-dev");
    assertThat(result.getKey()).isEqualTo("docs/a.pdf");
    assertThat(result.getEtag()).isEqualTo("etag-docs/a.pdf");
    assertThat(result.getSizeBytes()).isEqualTo(5L);
    assertThat(gateway.objects.get("ragforge-dev/docs/a.pdf")).isEqualTo("hello".getBytes());
    assertThat(gateway.metadata.get("ragforge-dev/docs/a.pdf").getContentType())
        .isEqualTo("application/pdf");
    assertThat(gateway.metadata.get("ragforge-dev/docs/a.pdf").getUserMetadata())
        .containsEntry("source", "unit-test");
  }

  @Test
  void get_readsObject() throws Exception {
    FakeOssGateway gateway = new FakeOssGateway();
    gateway.objects.put("ragforge-dev/docs/a.txt", "hello".getBytes());
    AliyunOssStorage storage = new AliyunOssStorage(gateway, "ragforge-dev", true);

    try (InputStream in = storage.get("ragforge-dev", "docs/a.txt")) {
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
    }
  }

  @Test
  void head_returnsMetadataOrNull() {
    FakeOssGateway gateway = new FakeOssGateway();
    ObjectMetadata metadata = new ObjectMetadata();
    metadata.setContentType("text/plain");
    metadata.setContentLength(5);
    metadata.setHeader("ETag", "etag-a");
    metadata.addUserMetadata("kind", "note");
    gateway.metadata.put("ragforge-dev/docs/a.txt", metadata);
    AliyunOssStorage storage = new AliyunOssStorage(gateway, "ragforge-dev", true);

    ObjectMeta found = storage.head("ragforge-dev", "docs/a.txt");
    ObjectMeta missing = storage.head("ragforge-dev", "docs/missing.txt");

    assertThat(found).isNotNull();
    assertThat(found.getContentType()).isEqualTo("text/plain");
    assertThat(found.getSizeBytes()).isEqualTo(5L);
    assertThat(found.getEtag()).isEqualTo("etag-a");
    assertThat(found.getUserMeta()).containsEntry("kind", "note");
    assertThat(missing).isNull();
  }

  @Test
  void exists_usesHead() {
    FakeOssGateway gateway = new FakeOssGateway();
    gateway.metadata.put("ragforge-dev/docs/a.txt", new ObjectMetadata());
    AliyunOssStorage storage = new AliyunOssStorage(gateway, "ragforge-dev", true);

    assertThat(storage.exists("ragforge-dev", "docs/a.txt")).isTrue();
    assertThat(storage.exists("ragforge-dev", "docs/missing.txt")).isFalse();
  }

  @Test
  void delete_isIdempotent() {
    FakeOssGateway gateway = new FakeOssGateway();
    gateway.objects.put("ragforge-dev/docs/a.txt", "hello".getBytes());
    gateway.metadata.put("ragforge-dev/docs/a.txt", new ObjectMetadata());
    AliyunOssStorage storage = new AliyunOssStorage(gateway, "ragforge-dev", true);

    storage.delete("ragforge-dev", "docs/a.txt");
    storage.delete("ragforge-dev", "docs/a.txt");

    assertThat(gateway.objects).doesNotContainKey("ragforge-dev/docs/a.txt");
    assertThat(gateway.metadata).doesNotContainKey("ragforge-dev/docs/a.txt");
  }

  @Test
  void presignedUrlsUseRequestedMethodAndTtl() {
    FakeOssGateway gateway = new FakeOssGateway();
    AliyunOssStorage storage = new AliyunOssStorage(gateway, "ragforge-dev", true);

    String getUrl = storage.presignedGet("ragforge-dev", "docs/a.pdf", Duration.ofMinutes(5));
    String putUrl =
        storage.presignedPut(
            "ragforge-dev",
            "docs/a.pdf",
            Duration.ofMinutes(15),
            new ObjectMeta("application/pdf", 5L, null, null));

    assertThat(getUrl).contains("method=GET");
    assertThat(putUrl).contains("method=PUT");
    assertThat(gateway.lastTtl).isEqualTo(Duration.ofMinutes(15));
  }

  @Test
  void operationsWrapClientFailure() {
    FakeOssGateway gateway = new FakeOssGateway();
    gateway.failOperations = true;
    AliyunOssStorage storage = new AliyunOssStorage(gateway, "ragforge-dev", true);

    assertThatThrownBy(() -> storage.head("ragforge-dev", "docs/a.txt"))
        .isInstanceOf(StorageException.class)
        .hasMessageContaining("bucket=ragforge-dev")
        .hasMessageContaining("key=docs/a.txt");
  }

  private static ByteArrayInputStream bytes(String value) {
    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
  }

  private static final class FakeOssGateway implements AliyunOssStorage.OssGateway {
    private boolean bucketExists = true;
    private boolean failOperations;
    private Duration lastTtl;
    private final Map<String, byte[]> objects = new HashMap<>();
    private final Map<String, ObjectMetadata> metadata = new HashMap<>();

    @Override
    public boolean bucketExists(String bucket) {
      return bucketExists;
    }

    @Override
    public String put(String bucket, String key, InputStream in, ObjectMetadata meta) {
      failIfNeeded();
      try {
        String storageKey = storageKey(bucket, key);
        objects.put(storageKey, in.readAllBytes());
        metadata.put(storageKey, meta);
        return "etag-" + key;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public InputStream get(String bucket, String key) {
      failIfNeeded();
      byte[] bytes = objects.get(storageKey(bucket, key));
      if (bytes == null) {
        throw new RuntimeException("not found");
      }
      return new ByteArrayInputStream(bytes);
    }

    @Override
    public ObjectMetadata head(String bucket, String key) {
      failIfNeeded();
      return metadata.get(storageKey(bucket, key));
    }

    @Override
    public String presigned(
        String bucket, String key, HttpMethod method, Duration ttl, ObjectMetadata meta) {
      failIfNeeded();
      lastTtl = ttl;
      return "https://oss.example/" + bucket + "/" + key + "?method=" + method.name();
    }

    @Override
    public void delete(String bucket, String key) {
      failIfNeeded();
      objects.remove(storageKey(bucket, key));
      metadata.remove(storageKey(bucket, key));
    }

    private void failIfNeeded() {
      if (failOperations) {
        throw new RuntimeException("oss down");
      }
    }

    private String storageKey(String bucket, String key) {
      return bucket + "/" + key;
    }
  }
}
