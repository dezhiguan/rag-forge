package com.ragforge.archive;

/**
 * 一个已通过全部护栏、被完整读入内存（受单 entry 上限约束，≤50MB）的压缩包子文件。 由 {@link ArchiveExpander}
 * 产出并回调 {@link ArchiveEntryConsumer}；Consumer 据此落 OSS + 登记子文档。
 *
 * <p>不落本地磁盘：内容在内存中按单 entry 上限受控缓冲，md5 与 size 已算好，Consumer 无需再做流式摘要。
 */
public class ExpandedEntry {

  /** 在压缩包内规范化后的安全相对路径（用于展示 / archive_entry_path）。 */
  private final String entryPath;

  /** 叶子文件名（用于 storageKey / filename）。 */
  private final String filename;

  /** 文件内容（已受单 entry 上限约束）。 */
  private final byte[] content;

  /** 内容 SHA-256 十六进制（用于 identity.contentMd5 去重）。 */
  private final String contentMd5;

  public ExpandedEntry(String entryPath, String filename, byte[] content, String contentMd5) {
    this.entryPath = entryPath;
    this.filename = filename;
    this.content = content;
    this.contentMd5 = contentMd5;
  }

  public String getEntryPath() {
    return entryPath;
  }

  public String getFilename() {
    return filename;
  }

  public byte[] getContent() {
    return content;
  }

  public String getContentMd5() {
    return contentMd5;
  }

  public long getSize() {
    return content == null ? 0L : content.length;
  }
}
