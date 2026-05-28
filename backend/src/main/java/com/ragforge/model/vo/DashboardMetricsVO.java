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
  private List<DashboardActivityVO> recentActivities;
}

