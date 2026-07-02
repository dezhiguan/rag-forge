package com.ragforge.archive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次解压的汇总结果。序列化进容器 {@code expand_summary}：
 * {@code {"totalEntries":N,"registered":N,"skipped":[{"path","reason"}]}}。
 *
 * <p>{@code totalEntries} = 非目录 entry 总数；{@code registered} = 成功回调 Consumer 的 entry 数；
 * {@code skipped} = 各类跳过明细。三者关系：totalEntries = registered + skipped.size()。
 */
public class ExpandOutcome {

  private int totalEntries;
  private int registered;
  private List<SkipRecord> skipped = new ArrayList<>();

  public int getTotalEntries() {
    return totalEntries;
  }

  public void setTotalEntries(int totalEntries) {
    this.totalEntries = totalEntries;
  }

  public int getRegistered() {
    return registered;
  }

  public void setRegistered(int registered) {
    this.registered = registered;
  }

  public List<SkipRecord> getSkipped() {
    return skipped;
  }

  public void setSkipped(List<SkipRecord> skipped) {
    this.skipped = skipped == null ? new ArrayList<>() : skipped;
  }

  public void addSkip(String path, SkipReason reason) {
    this.skipped.add(new SkipRecord(path, reason));
  }

  public void incrementTotal() {
    this.totalEntries++;
  }

  public void incrementRegistered() {
    this.registered++;
  }

  public List<SkipRecord> skippedView() {
    return Collections.unmodifiableList(skipped);
  }
}
