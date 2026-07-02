package com.ragforge.archive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;

/**
 * zip 流式读取器。用 commons-compress 的 {@link ZipArchiveInputStream}（而非 JDK
 * {@code java.util.zip.ZipInputStream}）——因为 JDK 版无法暴露"加密标志位"，而检测加密包是设计硬要求。
 * 仍为纯流式、不落盘；UTF-8 优先并启用 Unicode 扩展字段以兼容中文/GBK 包名。
 */
final class ZipExtractor implements ArchiveReader {

  @Override
  public ArchiveFormat format() {
    return ArchiveFormat.ZIP;
  }

  @Override
  public void read(InputStream source, EntryVisitor visitor) throws IOException {
    // encoding=UTF-8, useUnicodeExtraFields=true, allowStoredEntriesWithDataDescriptor=true
    try (ZipArchiveInputStream zin =
        new ZipArchiveInputStream(source, StandardCharsets.UTF_8.name(), true, true)) {
      ZipArchiveEntry entry;
      while ((entry = zin.getNextEntry()) != null) {
        visitor.visit(new ZipRawEntry(entry, zin));
      }
    }
  }

  /** 单个 zip entry 视图。stream() 返回底层 zin，读到 -1 即当前 entry 结束，禁止关闭。 */
  private static final class ZipRawEntry implements RawEntry {

    private final ZipArchiveEntry entry;
    private final ZipArchiveInputStream zin;

    ZipRawEntry(ZipArchiveEntry entry, ZipArchiveInputStream zin) {
      this.entry = entry;
      this.zin = zin;
    }

    @Override
    public String name() {
      return entry.getName();
    }

    @Override
    public boolean directory() {
      return entry.isDirectory();
    }

    @Override
    public boolean symlink() {
      return entry.isUnixSymlink();
    }

    @Override
    public boolean encrypted() {
      return entry.getGeneralPurposeBit() != null && entry.getGeneralPurposeBit().usesEncryption();
    }

    @Override
    public long uncompressedSize() {
      return entry.getSize();
    }

    @Override
    public long compressedSize() {
      return entry.getCompressedSize();
    }

    @Override
    public InputStream stream() {
      return zin;
    }
  }
}
