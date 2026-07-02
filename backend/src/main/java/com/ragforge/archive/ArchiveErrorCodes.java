package com.ragforge.archive;

/**
 * 压缩包解压相关的机器错误码。容器级致命错误落到 {@code documents.error_msg}，并（若发生在 HTTP 请求内）经
 * {@code GlobalExceptionHandler} + {@code ErrorMessages} 翻译成中文提示。新增码必须同步在
 * {@code ErrorMessages} 登记中文，否则前端只显示原码。
 */
public final class ArchiveErrorCodes {

  private ArchiveErrorCodes() {}

  /** 单 entry 压缩比超阈（疑似解压炸弹）。 */
  public static final String SUSPICIOUS_RATIO = "ARCHIVE_SUSPICIOUS_RATIO";
  /** 解压后累计体积超阈。 */
  public static final String TOTAL_SIZE_EXCEEDED = "ARCHIVE_TOTAL_SIZE_EXCEEDED";
  /** entry 数量超阈。 */
  public static final String TOO_MANY_ENTRIES = "ARCHIVE_TOO_MANY_ENTRIES";
  /** 加密压缩包，暂不支持。 */
  public static final String ENCRYPTED_UNSUPPORTED = "ARCHIVE_ENCRYPTED_UNSUPPORTED";
  /** 压缩包内无任何可入库的有效文件。 */
  public static final String EMPTY = "ARCHIVE_EMPTY";
  /** 压缩包损坏 / 截断 / 格式不正确，无法解压。 */
  public static final String CORRUPTED = "ARCHIVE_CORRUPTED";
  /** 不支持的压缩格式（7z / rar / 其它），上传分流阶段拒绝。 */
  public static final String UNSUPPORTED_FORMAT = "UNSUPPORTED_ARCHIVE_FORMAT";
}
