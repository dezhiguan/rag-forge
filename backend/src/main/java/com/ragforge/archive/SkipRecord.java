package com.ragforge.archive;

/**
 * 一条跳过记录：entry 在压缩包内的相对路径 + 跳过原因。序列化进容器 {@code expand_summary.skipped[]}，
 * 供前端展示"跳过明细"tooltip。
 */
public class SkipRecord {

  private String path;
  private String reason;

  public SkipRecord() {}

  public SkipRecord(String path, SkipReason reason) {
    this.path = path;
    this.reason = reason == null ? null : reason.wireName();
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }
}
