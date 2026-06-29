package com.ragforge.model.vo;

import java.math.BigDecimal;
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

  // ===== 近 7 天口径扩展指标 =====
  /** 资产·入库成功率：completed/(completed+failed)，0~1。 */
  private double ingestSuccessRate;
  /** 健康·P50 检索延迟（ms）。 */
  private long p50LatencyMs;
  /** 健康·近 7 天检索请求数。 */
  private long periodApiCalls;
  /**
   * 健康·检索成功率（0~1）。当前 retrieval_logs 未记录错误状态，暂无数据来源，恒为 0，
   * 前端 >0 才渲染；待管线补采集后即可亮。
   */
  private double searchSuccessRate;
  /**
   * 质量·平均 rerank 分（仅精排策略）。当前日志未落 score 列，暂无数据来源，恒为 0，
   * 前端 >0 才渲染；待管线在 retrieval_logs 落 rerank score 后即可亮。
   */
  private double avgRerankScore;

  // ===== 成本（近 7 天，全平台口径——计量未按用户归属） =====
  /** 近 7 天总成本（元）。 */
  private BigDecimal totalCost;
  /** 近 7 天 Token 总量（input+output）。 */
  private long tokenTotal;
  /** embedding 成本（元）。 */
  private BigDecimal embeddingCost;
  /** rerank 成本（元）。 */
  private BigDecimal rerankCost;
  /** LLM（改写/应答/评测/OCR）成本（元）。 */
  private BigDecimal llmCost;

  // ===== 入库处理健康度（按当前口径的库范围聚合 documents.parse_status） =====
  private long ingestCompleted;
  private long ingestProcessing;
  private long ingestQueued;
  private long ingestFailed;

  /** 检索趋势（近 7 天逐日）。 */
  private List<DashboardTrendPointVO> retrievalTrend;

  private List<DashboardActivityVO> recentActivities;
}

