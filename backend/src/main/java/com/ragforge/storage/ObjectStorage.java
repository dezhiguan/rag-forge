package com.ragforge.storage;

import java.io.InputStream;
import java.time.Duration;

public interface ObjectStorage {
  PutResult put(String bucket, String key, InputStream in, ObjectMeta meta);

  InputStream get(String bucket, String key);

  ObjectMeta head(String bucket, String key);

  String presignedGet(String bucket, String key, Duration ttl);

  String presignedPut(String bucket, String key, Duration ttl, ObjectMeta meta);

  void delete(String bucket, String key);

  boolean exists(String bucket, String key);
}
