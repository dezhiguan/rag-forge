package com.ragforge.archive;

import com.ragforge.common.ArchiveLimits;
import com.ragforge.common.SupportedDocumentTypes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * {@link ArchiveExpander} 默认实现：按格式选读取器，逐 entry 施加护栏，缓冲通过项并回调 Consumer。
 *
 * <p>护栏施加顺序（对齐设计稿 §4）：
 *
 * <ol>
 *   <li>目录 entry 跳过、不计数
 *   <li>entry 数超阈 → 致命 {@code TOO_MANY_ENTRIES}
 *   <li>加密 → 致命 {@code ENCRYPTED_UNSUPPORTED}
 *   <li>符号/硬链接、路径穿越 → 跳过 {@code illegal_path}
 *   <li>嵌套压缩包 → 跳过 {@code nested_archive}
 *   <li>非白名单扩展名 → 跳过 {@code unsupported_type}
 *   <li>读取时增量校验：累计解压量超阈 → 致命 {@code TOTAL_SIZE_EXCEEDED}；压缩比超阈 → 致命
 *       {@code SUSPICIOUS_RATIO}；单 entry 超阈 → 跳过 {@code oversize}
 * </ol>
 *
 * <p>纯流式、不落盘：单 entry 受上限约束缓冲入内存；所有解压字节（含超限被丢弃的）都计入总量护栏，
 * 因此解压炸弹在累计到上限前必被致命护栏拦截。
 */
@Component
public class DefaultArchiveExpander implements ArchiveExpander {

  private static final int READ_BUFFER = 8192;

  private final ArchiveLimits limits;
  private final ArchiveReader zipExtractor;
  private final ArchiveReader tarGzExtractor;

  public DefaultArchiveExpander(ArchiveLimits limits) {
    this(limits, new ZipExtractor(), new TarGzExtractor());
  }

  /** 测试可注入自定义读取器以穷举护栏分支（生产走默认构造，读取器为真实 zip/tar.gz 实现）。 */
  DefaultArchiveExpander(ArchiveLimits limits, ArchiveReader zipExtractor, ArchiveReader tarGzExtractor) {
    this.limits = limits;
    this.zipExtractor = zipExtractor;
    this.tarGzExtractor = tarGzExtractor;
  }

  @Override
  public ExpandOutcome expand(
      InputStream archiveStream, ArchiveFormat format, ArchiveEntryConsumer consumer) {
    ArchiveReader reader = readerFor(format);
    ExpandOutcome outcome = new ExpandOutcome();
    long[] totalBytes = {0L};
    // 计压缩包读入字节，用于"整包"压缩比护栏 —— tar.gz 的 gzip 层无逐 entry 压缩前大小，
    // 只能在整包层面比对 解压总量/压缩读入量，才能对 tar.gz 解压炸弹也施加比值护栏。
    CountingInputStream counted = new CountingInputStream(archiveStream);
    try {
      reader.read(counted, entry -> handleEntry(entry, outcome, totalBytes, counted, consumer));
    } catch (ArchiveException ae) {
      throw ae; // 致命护栏：向上传播，容器置 FAILED
    } catch (IOException | RuntimeException e) {
      throw new ArchiveException(
          ArchiveErrorCodes.CORRUPTED, "archive read failed: " + e.getMessage(), e);
    }
    if (outcome.getTotalEntries() == 0) {
      throw new ArchiveException(ArchiveErrorCodes.EMPTY, "archive has no file entries");
    }
    return outcome;
  }

  private ArchiveReader readerFor(ArchiveFormat format) {
    if (format == ArchiveFormat.ZIP) {
      return zipExtractor;
    }
    if (format == ArchiveFormat.TAR_GZ) {
      return tarGzExtractor;
    }
    throw new ArchiveException(
        ArchiveErrorCodes.UNSUPPORTED_FORMAT, "unsupported archive format: " + format);
  }

  private void handleEntry(
      RawEntry entry,
      ExpandOutcome outcome,
      long[] totalBytes,
      CountingInputStream counted,
      ArchiveEntryConsumer consumer)
      throws IOException {
    if (entry.directory()) {
      return; // 目录不计数、不入库
    }
    outcome.incrementTotal();
    if (outcome.getTotalEntries() > limits.getMaxEntries()) {
      throw new ArchiveException(
          ArchiveErrorCodes.TOO_MANY_ENTRIES, "entry count exceeds " + limits.getMaxEntries());
    }
    if (entry.encrypted()) {
      throw new ArchiveException(
          ArchiveErrorCodes.ENCRYPTED_UNSUPPORTED, "encrypted entry: " + entry.name());
    }

    String rawName = entry.name();
    if (entry.symlink()) {
      outcome.addSkip(rawName, SkipReason.ILLEGAL_PATH);
      return;
    }
    if (ArchiveGuardrails.isNestedArchive(rawName)) {
      outcome.addSkip(rawName, SkipReason.NESTED_ARCHIVE);
      return;
    }
    String safePath = ArchiveGuardrails.normalizePath(rawName);
    if (safePath == null) {
      outcome.addSkip(rawName, SkipReason.ILLEGAL_PATH);
      return;
    }
    if (!SupportedDocumentTypes.isAllowed(safePath)) {
      outcome.addSkip(safePath, SkipReason.UNSUPPORTED_TYPE);
      return;
    }

    byte[] content = readGuarded(entry, totalBytes, counted);
    if (content == null) {
      // 单 entry 超阈：跳过，不失败整包
      outcome.addSkip(safePath, SkipReason.OVERSIZE);
      return;
    }

    ExpandedEntry expanded =
        new ExpandedEntry(safePath, ArchiveGuardrails.leafName(safePath), content, sha256(content));
    try {
      consumer.consume(expanded);
      outcome.incrementRegistered();
    } catch (Exception e) {
      outcome.addSkip(safePath, SkipReason.REGISTER_FAILED);
    }
  }

  /**
   * 增量护栏读取：把 entry 全量读出以计入总量护栏（防炸弹），缓冲上限为单 entry 阈值。 返回内容字节；单 entry
   * 超阈返回 {@code null}（调用方记 oversize 跳过）。 触发累计总量 / 压缩比护栏时抛致命 {@link
   * ArchiveException}。
   */
  private byte[] readGuarded(RawEntry entry, long[] totalBytes, CountingInputStream counted)
      throws IOException {
    long compressed = entry.compressedSize();
    long maxRatio = limits.getMaxCompressionRatio();
    long maxEntry = limits.getMaxEntryBytes();
    long maxTotal = limits.getMaxTotalUncompressedBytes();

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    long entryBytes = 0;
    boolean oversize = false;
    byte[] tmp = new byte[READ_BUFFER];
    InputStream in = entry.stream();
    int n;
    while ((n = in.read(tmp)) != -1) {
      entryBytes += n;
      totalBytes[0] += n;
      if (totalBytes[0] > maxTotal) {
        throw new ArchiveException(
            ArchiveErrorCodes.TOTAL_SIZE_EXCEEDED,
            "uncompressed total exceeds " + maxTotal + " bytes");
      }
      // 压缩比护栏（增量、早停）：
      //  a) 逐 entry —— 压缩前大小已知时（zip 精确）
      //  b) 整包 —— 解压总量/压缩读入量（tar.gz 兜底，gzip 无逐 entry 压缩前大小）
      if (maxRatio > 0 && compressed > 0 && entryBytes > compressed * maxRatio) {
        throw new ArchiveException(
            ArchiveErrorCodes.SUSPICIOUS_RATIO,
            "compression ratio exceeds " + maxRatio + ":1 for " + entry.name());
      }
      long compressedSoFar = counted.getCount();
      if (maxRatio > 0 && compressedSoFar > 0 && totalBytes[0] > compressedSoFar * maxRatio) {
        throw new ArchiveException(
            ArchiveErrorCodes.SUSPICIOUS_RATIO,
            "archive compression ratio exceeds " + maxRatio + ":1");
      }
      if (!oversize) {
        if (entryBytes > maxEntry) {
          oversize = true;
          buffer.reset(); // 释放缓冲，仅继续计数（保持炸弹防护）
        } else {
          buffer.write(tmp, 0, n);
        }
      }
    }
    return oversize ? null : buffer.toByteArray();
  }

  private static String sha256(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** 统计从底层压缩包流累计读入的字节数，用于整包压缩比护栏。 */
  private static final class CountingInputStream extends java.io.FilterInputStream {
    private long count;

    CountingInputStream(InputStream in) {
      super(in);
    }

    long getCount() {
      return count;
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b != -1) {
        count++;
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int n = super.read(b, off, len);
      if (n > 0) {
        count += n;
      }
      return n;
    }
  }
}
