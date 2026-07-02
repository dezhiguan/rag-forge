package com.ragforge.archive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * tar.gz 流式读取器：先 gunzip（{@link GzipCompressorInputStream}）再解 tar
 * （{@link TarArchiveInputStream}），逐 entry 迭代。纯流式、不落盘。tar 无加密概念；tar header 携带精确
 * 解压后大小，可用于单 entry 上限预判。符号/硬链接由上层按非法路径跳过（不跟随）。
 */
final class TarGzExtractor implements ArchiveReader {

  @Override
  public ArchiveFormat format() {
    return ArchiveFormat.TAR_GZ;
  }

  @Override
  public void read(InputStream source, EntryVisitor visitor) throws IOException {
    try (GzipCompressorInputStream gz = new GzipCompressorInputStream(source);
        TarArchiveInputStream tin = new TarArchiveInputStream(gz, StandardCharsets.UTF_8.name())) {
      TarArchiveEntry entry;
      while ((entry = tin.getNextEntry()) != null) {
        visitor.visit(new TarRawEntry(entry, tin));
      }
    }
  }

  private static final class TarRawEntry implements RawEntry {

    private final TarArchiveEntry entry;
    private final TarArchiveInputStream tin;

    TarRawEntry(TarArchiveEntry entry, TarArchiveInputStream tin) {
      this.entry = entry;
      this.tin = tin;
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
      return entry.isSymbolicLink() || entry.isLink();
    }

    @Override
    public boolean encrypted() {
      return false;
    }

    @Override
    public long uncompressedSize() {
      return entry.getSize();
    }

    @Override
    public long compressedSize() {
      return -1L;
    }

    @Override
    public InputStream stream() {
      return tin;
    }
  }
}
