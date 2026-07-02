package com.ragforge.archive;

/**
 * 解压过程中的致命错误（整包失败）。{@code code} 为 {@link ArchiveErrorCodes} 中的机器码，供
 * Consumer 写入容器 {@code error_msg}，前端据此翻译中文。区别于"跳过单个 entry"（后者用
 * {@link SkipRecord} 记录，不失败整包）。
 */
public class ArchiveException extends RuntimeException {

  private final String code;

  public ArchiveException(String code, String message) {
    super(message);
    this.code = code;
  }

  public ArchiveException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
