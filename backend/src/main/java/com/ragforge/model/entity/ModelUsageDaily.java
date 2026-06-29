package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 模型用量日级汇总：成本看板主数据源，由 ModelUsageRecorder 批量 UPSERT 写入。 */
@Data
@TableName("model_usage_daily")
public class ModelUsageDaily {

  @TableId(type = IdType.AUTO)
  private Long id;

  @TableField("model_code")
  private String modelCode;

  private String purpose;

  @TableField("stat_date")
  private LocalDate statDate;

  /** 成本归属组织（0 = 未归属/平台级，如评测）。V50 新增。 */
  @TableField("org_id")
  private Long orgId;

  @TableField("call_count")
  private Long callCount;

  @TableField("input_tokens")
  private Long inputTokens;

  @TableField("output_tokens")
  private Long outputTokens;

  /** 元 */
  private BigDecimal cost;

  @TableField("success_count")
  private Long successCount;

  @TableField("fail_count")
  private Long failCount;

  @TableField("total_latency_ms")
  private Long totalLatencyMs;

  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
