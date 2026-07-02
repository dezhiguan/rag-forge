package com.ragforge.archive;

/**
 * 单个 entry 被跳过的原因（不失败整包，记入 {@link SkipRecord} → 容器 expand_summary）。 {@link #wireName()}
 * 为落库/前端展示用的稳定标识，对齐设计稿 §4。
 */
public enum SkipReason {
  /** entry 本身是压缩包（zip/tar/gz/7z/rar），不递归解压。 */
  NESTED_ARCHIVE("nested_archive"),
  /** 路径穿越（.. / 绝对路径 / 反斜杠越界 / symlink 越界）。 */
  ILLEGAL_PATH("illegal_path"),
  /** 非白名单扩展名（复用文档解析白名单）。 */
  UNSUPPORTED_TYPE("unsupported_type"),
  /** 单 entry 解压后大小超过上限。 */
  OVERSIZE("oversize"),
  /** 该 entry 落 OSS / register 失败（罕见，设计稿 4 类之外的运行期兜底，记录以便排查）。 */
  REGISTER_FAILED("register_failed");

  private final String wireName;

  SkipReason(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }
}
