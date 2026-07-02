package com.ragforge.model.vo;

import lombok.Data;

/**
 * 压缩包容器子文档进度汇总。实时按子文档 parse_status 分组计算，不落冗余计数，避免状态漂移。
 *
 * <p>{@code total} 为已登记子文档数（各状态计数之和）；{@code skipped} 为容器 expand_summary
 * 中记录的跳过 entry 数（未登记为子文档）。
 */
@Data
public class ChildrenSummaryVO {

  private int total;
  private int pending;
  private int processing;
  private int completed;
  private int failed;
  private int skipped;
}
