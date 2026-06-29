package com.ragforge.model.vo;

import lombok.Data;

/** 检索趋势的单日数据点（近 7 天逐日聚合）。 */
@Data
public class DashboardTrendPointVO {

  /** 日期标签，格式 MM-DD。 */
  private String date;
  /** 当日检索请求数。 */
  private long count;
  /** 当日 P95 检索延迟（ms）。 */
  private long p95LatencyMs;
}
