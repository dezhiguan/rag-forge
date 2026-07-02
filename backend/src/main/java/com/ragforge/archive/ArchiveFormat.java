package com.ragforge.archive;

/**
 * 支持识别的压缩包格式。{@link #ZIP} 与 {@link #TAR_GZ} 为本期支持解压的格式；{@link #SEVEN_Z} /
 * {@link #RAR} 仅用于在上传分流阶段识别并拒绝（返回友好提示，不落容器）。{@link #UNKNOWN} 表示非压缩包。
 *
 * <p>格式判定以文件头 magic number 为准，不依赖扩展名（见 {@link ArchiveFormatDetector}）。
 */
public enum ArchiveFormat {
  ZIP("zip", true),
  TAR_GZ("tar.gz", true),
  SEVEN_Z("7z", false),
  RAR("rar", false),
  UNKNOWN(null, false);

  private final String token;
  private final boolean supported;

  ArchiveFormat(String token, boolean supported) {
    this.token = token;
    this.supported = supported;
  }

  /** 落库到 {@code documents.file_type} 的格式标识（zip / tar.gz）；非压缩包返回 null。 */
  public String token() {
    return token;
  }

  /** 是否为本期支持解压的格式。7z / rar / 非压缩包返回 false。 */
  public boolean isSupported() {
    return supported;
  }

  /** 是否被识别为压缩包（含暂不支持的 7z / rar）。 */
  public boolean isArchive() {
    return this != UNKNOWN;
  }
}
