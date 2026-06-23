package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("judge_metrics_daily")
public class JudgeMetricsDaily {

  @TableId(type = IdType.AUTO)
  private Long id;
  private LocalDate date;
  private Long kbId;
  private String tenantId;
  private Integer sampleCount;
  private Integer failedCount;
  private BigDecimal faithfulnessP50;
  private BigDecimal faithfulnessP95;
  private BigDecimal contextPrecisionP50;
  private BigDecimal contextPrecisionP95;
  private BigDecimal answerRelevanceP50;
  private BigDecimal answerRelevanceP95;
  private BigDecimal overallP50;
  private BigDecimal overallP95;
  private BigDecimal overallMean;
  private BigDecimal overallStd;
  private BigDecimal totalCostCny;
  private LocalDateTime updatedAt;
}
