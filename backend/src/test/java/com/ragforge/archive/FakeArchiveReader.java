package com.ragforge.archive;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 测试用假读取器：按预置的 entry 列表逐个回调；可选在迭代前抛 IOException 模拟损坏包。 */
class FakeArchiveReader implements ArchiveReader {

  private final ArchiveFormat format;
  private final List<RawEntry> entries = new ArrayList<>();
  private IOException failure;

  FakeArchiveReader(ArchiveFormat format) {
    this.format = format;
  }

  FakeArchiveReader add(RawEntry e) {
    entries.add(e);
    return this;
  }

  FakeArchiveReader failWith(IOException e) {
    this.failure = e;
    return this;
  }

  @Override
  public ArchiveFormat format() {
    return format;
  }

  @Override
  public void read(InputStream source, EntryVisitor visitor) throws IOException {
    if (failure != null) {
      throw failure;
    }
    for (RawEntry e : entries) {
      visitor.visit(e);
    }
  }

  /** 构造一个文件 entry。 */
  static RawEntry file(String name, byte[] content) {
    return new FakeRawEntry(name, false, false, false, content, -1);
  }

  static RawEntry file(String name, String content) {
    return file(name, content.getBytes(StandardCharsets.UTF_8));
  }

  static RawEntry dir(String name) {
    return new FakeRawEntry(name, true, false, false, new byte[0], -1);
  }

  static RawEntry symlink(String name) {
    return new FakeRawEntry(name, false, true, false, new byte[0], -1);
  }

  static RawEntry encrypted(String name) {
    return new FakeRawEntry(name, false, false, true, new byte[0], -1);
  }

  /** 指定压缩前大小（用于压缩比护栏）。 */
  static RawEntry withCompressed(String name, byte[] content, long compressedSize) {
    return new FakeRawEntry(name, false, false, false, content, compressedSize);
  }

  private static final class FakeRawEntry implements RawEntry {
    private final String name;
    private final boolean directory;
    private final boolean symlink;
    private final boolean encrypted;
    private final byte[] content;
    private final long compressed;

    FakeRawEntry(
        String name,
        boolean directory,
        boolean symlink,
        boolean encrypted,
        byte[] content,
        long compressed) {
      this.name = name;
      this.directory = directory;
      this.symlink = symlink;
      this.encrypted = encrypted;
      this.content = content;
      this.compressed = compressed;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public boolean directory() {
      return directory;
    }

    @Override
    public boolean symlink() {
      return symlink;
    }

    @Override
    public boolean encrypted() {
      return encrypted;
    }

    @Override
    public long uncompressedSize() {
      return content.length;
    }

    @Override
    public long compressedSize() {
      return compressed;
    }

    @Override
    public InputStream stream() {
      return new ByteArrayInputStream(content);
    }
  }
}
