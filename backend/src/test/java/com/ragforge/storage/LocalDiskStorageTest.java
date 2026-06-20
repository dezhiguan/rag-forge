package com.ragforge.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDiskStorageTest {

  @TempDir Path tempDir;

  @Test
  void putAndGetUseV4FlatRootPath() throws Exception {
    LocalDiskStorage storage = new LocalDiskStorage(tempDir.toString());

    PutResult result =
        storage.put(
            "local",
            "resume.pdf",
            new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)),
            new ObjectMeta("application/pdf", 5L, null, null));

    assertThat(result.getBucket()).isEqualTo("local");
    assertThat(result.getKey()).isEqualTo("resume.pdf");
    assertThat(result.getSizeBytes()).isEqualTo(5L);
    assertThat(Files.exists(tempDir.resolve("resume.pdf"))).isTrue();
    try (InputStream in = storage.get("local", "resume.pdf")) {
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
    }
  }

  @Test
  void headExistsAndDeleteAreIdempotent() {
    LocalDiskStorage storage = new LocalDiskStorage(tempDir.toString());
    storage.put("local", "note.md", new ByteArrayInputStream("hello".getBytes()), null);

    ObjectMeta meta = storage.head("local", "note.md");

    assertThat(meta).isNotNull();
    assertThat(meta.getSizeBytes()).isEqualTo(5L);
    assertThat(storage.exists("local", "note.md")).isTrue();
    storage.delete("local", "note.md");
    storage.delete("local", "note.md");
    assertThat(storage.head("local", "note.md")).isNull();
    assertThat(storage.exists("local", "note.md")).isFalse();
  }

  @Test
  void rejectAbsoluteOrEscapingKey() {
    LocalDiskStorage storage = new LocalDiskStorage(tempDir.toString());

    assertThatThrownBy(
            () -> storage.put("local", "../secret.txt", new ByteArrayInputStream(new byte[0]), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                storage.put(
                    "local",
                    tempDir.resolve("abs.txt").toString(),
                    new ByteArrayInputStream(new byte[0]),
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void presignedUrlsReturnLocalFileUri() {
    LocalDiskStorage storage = new LocalDiskStorage(tempDir.toString());

    assertThat(storage.presignedGet("local", "a.txt", java.time.Duration.ofMinutes(5)))
        .startsWith("file:");
    assertThat(storage.presignedPut("local", "a.txt", java.time.Duration.ofMinutes(5), null))
        .contains("method=PUT");
  }

  @Test
  void putHeadGetStreamOneHundredMbAndPreserveMd5() throws Exception {
    LocalDiskStorage storage = new LocalDiskStorage(tempDir.toString());
    Path source = tempDir.resolve("source-100mb.pdf");
    writePdfLikeFile(source, 100L * 1024 * 1024);
    String expectedMd5 = md5(source);

    try (InputStream in = Files.newInputStream(source)) {
      PutResult result =
          storage.put(
              "local",
              "large.pdf",
              in,
              new ObjectMeta("application/pdf", Files.size(source), null, null));
      assertThat(result.getSizeBytes()).isEqualTo(Files.size(source));
    }

    ObjectMeta head = storage.head("local", "large.pdf");
    assertThat(head).isNotNull();
    assertThat(head.getSizeBytes()).isEqualTo(Files.size(source));
    try (InputStream downloaded = storage.get("local", "large.pdf")) {
      assertThat(md5(downloaded)).isEqualTo(expectedMd5);
    }
  }

  private static void writePdfLikeFile(Path path, long sizeBytes) throws Exception {
    byte[] buffer = new byte[1024 * 1024];
    byte[] header = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(header, 0, buffer, 0, header.length);
    long written = 0;
    try (var out = Files.newOutputStream(path)) {
      while (written < sizeBytes) {
        int next = (int) Math.min(buffer.length, sizeBytes - written);
        out.write(buffer, 0, next);
        written += next;
      }
    }
  }

  private static String md5(Path path) throws Exception {
    try (InputStream in = Files.newInputStream(path)) {
      return md5(in);
    }
  }

  private static String md5(InputStream source) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("MD5");
    try (InputStream in = new DigestInputStream(source, digest)) {
      byte[] buffer = new byte[1024 * 1024];
      while (in.read(buffer) != -1) {
        // Drain stream into digest.
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
