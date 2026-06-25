package com.ragforge.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.PutObjectResult;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AliyunOssStorage implements ObjectStorage {
  private final OssGateway oss;
  private final String defaultBucket;

  public AliyunOssStorage(StorageProperties properties) {
    this(new DefaultOssGateway(properties.getAliyun()), properties.getAliyun().getBucket(), true);
  }

  AliyunOssStorage(OssGateway oss, String defaultBucket, boolean validateBucket) {
    this.oss = oss;
    this.defaultBucket = requireText(defaultBucket, "storage.aliyun.bucket");
    if (validateBucket) {
      headBucket(this.defaultBucket);
    }
  }

  @Override
  public PutResult put(String bucket, String key, InputStream in, ObjectMeta meta) {
    if (in == null) {
      throw new IllegalArgumentException("input stream is required");
    }
    String resolvedBucket = resolveBucket(bucket);
    String resolvedKey = requireText(key, "object key");
    ObjectMetadata metadata = toOssMeta(meta);
    try {
      String etag = oss.put(resolvedBucket, resolvedKey, in, metadata);
      Long sizeBytes = meta == null ? null : meta.getSizeBytes();
      return new PutResult(resolvedBucket, resolvedKey, etag, sizeBytes);
    } catch (RuntimeException e) {
      throw storageFailure("Failed to put OSS object", resolvedBucket, resolvedKey, e);
    }
  }

  @Override
  public InputStream get(String bucket, String key) {
    String resolvedBucket = resolveBucket(bucket);
    String resolvedKey = requireText(key, "object key");
    try {
      return oss.get(resolvedBucket, resolvedKey);
    } catch (RuntimeException e) {
      throw storageFailure("Failed to get OSS object", resolvedBucket, resolvedKey, e);
    }
  }

  @Override
  public ObjectMeta head(String bucket, String key) {
    String resolvedBucket = resolveBucket(bucket);
    String resolvedKey = requireText(key, "object key");
    try {
      ObjectMetadata metadata = oss.head(resolvedBucket, resolvedKey);
      return metadata == null ? null : fromOssMeta(metadata);
    } catch (RuntimeException e) {
      throw storageFailure("Failed to head OSS object", resolvedBucket, resolvedKey, e);
    }
  }

  @Override
  public String presignedGet(String bucket, String key, Duration ttl) {
    return presigned(bucket, key, ttl, HttpMethod.GET, null);
  }

  @Override
  public String presignedPut(String bucket, String key, Duration ttl, ObjectMeta meta) {
    return presigned(bucket, key, ttl, HttpMethod.PUT, meta);
  }

  @Override
  public void delete(String bucket, String key) {
    String resolvedBucket = resolveBucket(bucket);
    String resolvedKey = requireText(key, "object key");
    try {
      oss.delete(resolvedBucket, resolvedKey);
    } catch (RuntimeException e) {
      throw storageFailure("Failed to delete OSS object", resolvedBucket, resolvedKey, e);
    }
  }

  @Override
  public boolean exists(String bucket, String key) {
    return head(bucket, key) != null;
  }

  public List<OssObjectInfo> listObjects(String bucket, String prefix) {
    String resolvedBucket = resolveBucket(bucket);
    String resolvedPrefix = prefix == null ? "" : prefix;
    try {
      return oss.list(resolvedBucket, resolvedPrefix).stream()
          .map(
              item ->
                  new OssObjectInfo(
                      item.getKey(),
                      item.getLastModified() == null ? null : item.getLastModified().toInstant(),
                      item.getSize()))
          .toList();
    } catch (RuntimeException e) {
      throw storageFailure("Failed to list OSS objects", resolvedBucket, resolvedPrefix, e);
    }
  }

  private String presigned(
      String bucket, String key, Duration ttl, HttpMethod method, ObjectMeta objectMeta) {
    if (ttl == null || ttl.isNegative() || ttl.isZero()) {
      throw new IllegalArgumentException("ttl must be positive");
    }
    String resolvedBucket = resolveBucket(bucket);
    String resolvedKey = requireText(key, "object key");
    try {
      return oss.presigned(resolvedBucket, resolvedKey, method, ttl, toOssMeta(objectMeta));
    } catch (RuntimeException e) {
      throw storageFailure("Failed to presign OSS object", resolvedBucket, resolvedKey, e);
    }
  }

  private void headBucket(String bucket) {
    try {
      if (!oss.bucketExists(bucket)) {
        throw new StorageException("OSS bucket does not exist or is not accessible: " + bucket);
      }
    } catch (RuntimeException e) {
      if (e instanceof StorageException) {
        throw e;
      }
      throw new StorageException("OSS bucket connectivity check failed: " + bucket, e);
    }
  }

  private String resolveBucket(String bucket) {
    return bucket == null || bucket.isBlank() ? defaultBucket : bucket;
  }

  private static ObjectMetadata toOssMeta(ObjectMeta meta) {
    ObjectMetadata metadata = new ObjectMetadata();
    if (meta == null) {
      return metadata;
    }
    if (meta.getContentType() != null && !meta.getContentType().isBlank()) {
      metadata.setContentType(meta.getContentType());
    }
    if (meta.getSizeBytes() != null) {
      metadata.setContentLength(meta.getSizeBytes());
    }
    if (meta.getUserMeta() != null) {
      meta.getUserMeta().forEach(metadata::addUserMetadata);
    }
    return metadata;
  }

  private static ObjectMeta fromOssMeta(ObjectMetadata metadata) {
    Map<String, String> userMeta =
        metadata.getUserMetadata() == null ? Map.of() : new HashMap<>(metadata.getUserMetadata());
    return new ObjectMeta(
        metadata.getContentType(), metadata.getContentLength(), metadata.getETag(), userMeta);
  }

  private static StorageException storageFailure(
      String message, String bucket, String key, RuntimeException cause) {
    // 把 OSS 真实错误码（NoSuchKey / AccessDenied / SignatureDoesNotMatch 等）抽到顶层 message，
    // 否则只看 e.getMessage() 永远只看到 "Failed to get/put OSS object: ..."，定位不到根因。
    String ossCode = extractOssErrorCode(cause);
    String detail = message + ": bucket=" + bucket + ", key=" + key;
    if (ossCode != null) {
      detail += " ossErrorCode=" + ossCode;
    }
    return new StorageException(detail, cause);
  }

  private static String extractOssErrorCode(Throwable cause) {
    Throwable cursor = cause;
    while (cursor != null) {
      if (cursor instanceof OSSException oss) {
        return oss.getErrorCode();
      }
      cursor = cursor.getCause();
    }
    return null;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }

  interface OssGateway {
    boolean bucketExists(String bucket);

    String put(String bucket, String key, InputStream in, ObjectMetadata meta);

    InputStream get(String bucket, String key);

    ObjectMetadata head(String bucket, String key);

    String presigned(String bucket, String key, HttpMethod method, Duration ttl, ObjectMetadata meta);

    List<OSSObjectSummary> list(String bucket, String prefix);

    void delete(String bucket, String key);
  }

  public record OssObjectInfo(String key, Instant lastModified, Long sizeBytes) {}

  private static final class DefaultOssGateway implements OssGateway {
    private final OSS client;

    private DefaultOssGateway(StorageProperties.Aliyun properties) {
      String endpoint = requireText(properties.getEndpoint(), "storage.aliyun.endpoint");
      String accessKeyId = requireText(properties.getAccessKeyId(), "storage.aliyun.accessKeyId");
      String accessKeySecret =
          requireText(properties.getAccessKeySecret(), "storage.aliyun.accessKeySecret");
      this.client = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
    }

    @Override
    public boolean bucketExists(String bucket) {
      return client.doesBucketExist(bucket);
    }

    @Override
    public String put(String bucket, String key, InputStream in, ObjectMetadata meta) {
      PutObjectResult result = client.putObject(new PutObjectRequest(bucket, key, in, meta));
      return result.getETag();
    }

    @Override
    public InputStream get(String bucket, String key) {
      OSSObject object = client.getObject(bucket, key);
      return object.getObjectContent();
    }

    @Override
    public ObjectMetadata head(String bucket, String key) {
      try {
        return client.getObjectMetadata(bucket, key);
      } catch (OSSException e) {
        if (isNotFound(e)) {
          return null;
        }
        throw e;
      }
    }

    @Override
    public String presigned(
        String bucket, String key, HttpMethod method, Duration ttl, ObjectMetadata meta) {
      Date expiration = Date.from(Instant.now().plus(ttl));
      GeneratePresignedUrlRequest request =
          new GeneratePresignedUrlRequest(bucket, key, method);
      request.setExpiration(expiration);
      if (meta != null && meta.getContentType() != null) {
        request.setContentType(meta.getContentType());
      }
      URL url = client.generatePresignedUrl(request);
      return url.toString();
    }

    @Override
    public List<OSSObjectSummary> list(String bucket, String prefix) {
      List<OSSObjectSummary> summaries = new ArrayList<>();
      ListObjectsRequest request = new ListObjectsRequest(bucket);
      request.setPrefix(prefix);
      ObjectListing listing;
      do {
        listing = client.listObjects(request);
        summaries.addAll(listing.getObjectSummaries());
        request.setMarker(listing.getNextMarker());
      } while (listing.isTruncated());
      return summaries;
    }

    @Override
    public void delete(String bucket, String key) {
      client.deleteObject(bucket, key);
    }

    private boolean isNotFound(OSSException e) {
      String errorCode = e.getErrorCode();
      return "NoSuchKey".equals(errorCode)
          || "NoSuchBucket".equals(errorCode)
          || "404".equals(errorCode);
    }
  }
}
