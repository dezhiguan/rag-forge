package com.ragforge.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 压缩包解压护栏阈值（可经 {@code ragforge.archive.*} 配置覆盖）。默认值对齐设计稿 §4：
 *
 * <ul>
 *   <li>压缩比 &gt; 100:1 → 疑似解压炸弹（整包失败）
 *   <li>解压后总量 &gt; 500MB → 整包失败
 *   <li>entry 数 &gt; 1000 → 整包失败
 *   <li>单 entry &gt; 50MB（复用 {@link RelayUploadLimits}）→ 跳过该 entry（不失败整包）
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "ragforge.archive")
public class ArchiveLimits {

  /** 单 entry 解压后/压缩前比值上限；超过判为疑似炸弹。0 或负表示不启用比值护栏。 */
  private long maxCompressionRatio = 100L;

  /** 解压后累计字节上限。 */
  private long maxTotalUncompressedBytes = 500L * 1024 * 1024;

  /** entry 数量上限（不含目录 entry）。 */
  private int maxEntries = 1000;

  /** 单 entry 解压后字节上限（默认复用 relay 上传上限 50MB）。 */
  private long maxEntryBytes = RelayUploadLimits.RELAY_UPLOAD_LIMIT_BYTES;

  public long getMaxCompressionRatio() {
    return maxCompressionRatio;
  }

  public void setMaxCompressionRatio(long maxCompressionRatio) {
    this.maxCompressionRatio = maxCompressionRatio;
  }

  public long getMaxTotalUncompressedBytes() {
    return maxTotalUncompressedBytes;
  }

  public void setMaxTotalUncompressedBytes(long maxTotalUncompressedBytes) {
    this.maxTotalUncompressedBytes = maxTotalUncompressedBytes;
  }

  public int getMaxEntries() {
    return maxEntries;
  }

  public void setMaxEntries(int maxEntries) {
    this.maxEntries = maxEntries;
  }

  public long getMaxEntryBytes() {
    return maxEntryBytes;
  }

  public void setMaxEntryBytes(long maxEntryBytes) {
    this.maxEntryBytes = maxEntryBytes;
  }
}
