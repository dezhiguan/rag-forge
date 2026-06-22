package com.ragforge.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

public class LocalDiskStorage implements ObjectStorage {
  private final Path rootPath;

  public LocalDiskStorage(String rootPath) {
    if (rootPath == null || rootPath.isBlank()) {
      throw new IllegalArgumentException("storage.local.rootPath is required");
    }
    this.rootPath = Path.of(rootPath).toAbsolutePath().normalize();
  }

  @Override
  public PutResult put(String bucket, String key, InputStream in, ObjectMeta meta) {
    if (in == null) {
      throw new IllegalArgumentException("input stream is required");
    }
    Path target = resolveKey(key);
    try {
      Files.createDirectories(target.getParent());
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      CountingInputStream counting = new CountingInputStream(new DigestInputStream(in, sha256));
      Files.copy(counting, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      String etag = HexFormat.of().formatHex(sha256.digest());
      return new PutResult(resolveBucket(bucket), key, etag, counting.getBytesRead());
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new StorageException("Failed to put local object: " + key, e);
    }
  }

  @Override
  public InputStream get(String bucket, String key) {
    try {
      return Files.newInputStream(resolveKey(key));
    } catch (IOException e) {
      throw new StorageException("Failed to get local object: " + key, e);
    }
  }

  @Override
  public ObjectMeta head(String bucket, String key) {
    Path path = resolveKey(key);
    if (!Files.exists(path)) {
      return null;
    }
    try {
      ObjectMeta meta = new ObjectMeta();
      meta.setContentType(Files.probeContentType(path));
      meta.setSizeBytes(Files.size(path));
      return meta;
    } catch (IOException e) {
      throw new StorageException("Failed to head local object: " + key, e);
    }
  }

  @Override
  public String presignedGet(String bucket, String key, Duration ttl) {
    return resolveKey(key).toUri().toString();
  }

  @Override
  public String presignedPut(String bucket, String key, Duration ttl, ObjectMeta meta) {
    String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
    return resolveKey(key).toUri() + "?method=PUT&key=" + encodedKey;
  }

  @Override
  public void delete(String bucket, String key) {
    try {
      Files.deleteIfExists(resolveKey(key));
    } catch (IOException e) {
      throw new StorageException("Failed to delete local object: " + key, e);
    }
  }

  @Override
  public boolean exists(String bucket, String key) {
    return Files.exists(resolveKey(key));
  }

  private Path resolveKey(String key) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("object key is required");
    }
    Path relative = Path.of(key).normalize();
    if (relative.isAbsolute() || relative.startsWith("..")) {
      throw new IllegalArgumentException("object key must be relative to local rootPath");
    }
    Path resolved = rootPath.resolve(relative).normalize();
    if (!resolved.startsWith(rootPath)) {
      throw new IllegalArgumentException("object key escapes local rootPath");
    }
    return resolved;
  }

  private String resolveBucket(String bucket) {
    return bucket == null || bucket.isBlank() ? "local" : bucket;
  }

  private static final class CountingInputStream extends FilterInputStream {
    private long bytesRead;

    private CountingInputStream(InputStream in) {
      super(in);
    }

    @Override
    public int read() throws IOException {
      int result = super.read();
      if (result >= 0) {
        bytesRead++;
      }
      return result;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int result = super.read(b, off, len);
      if (result > 0) {
        bytesRead += result;
      }
      return result;
    }

    long getBytesRead() {
      return bytesRead;
    }
  }
}
