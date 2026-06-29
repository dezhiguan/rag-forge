package com.ragforge.model.vo;

import java.util.List;
import lombok.Data;

@Data
public class DashboardMetricsVO {

  private long kbCount;
  private long documentCount;
  private long chunkCount;
  private long todayApiCalls;
  private long avgLatencyMs;
  private double hitRate;

  // 四象限新增指标（均由 retrieval_logs 现有列直接聚合，无需改表）：
  /** 零结果率：返回 0 条的检索占比（0~1）。 */
  private double zeroResultRate;
  /** 平均召回条数。 */
  private double avgRecallCount;
  /** P95 检索延迟（ms）。 */
  private long p95LatencyMs;
  /** Query 改写率：rewritten_queries 非空占比（0~1）。 */
  private double rewriteRate;

  private List<DashboardActivityVO> recentActivities;
}

