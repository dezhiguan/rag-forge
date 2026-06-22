package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ragforge.mybatis.handler.JsonbStringTypeHandler;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName(value = "judge_results", autoResultMap = true)
public class JudgeResult {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long answerLogId;
  private Long[] kbIds;
  private String query;
  private BigDecimal faithfulness;
  private BigDecimal contextPrecision;
  private BigDecimal contextRecall;
  private BigDecimal answerRelevance;
  private BigDecimal completeness;
  private BigDecimal citationAccuracy;
  private BigDecimal overallScore;
  private String judgeModel;
  private String judgePromptVersion;
  private String judgeReasoning;

  @TableField(value = "judge_raw_response", typeHandler = JsonbStringTypeHandler.class)
  private String judgeRawResponse;

  private Integer judgeLatencyMs;
  private BigDecimal judgeCostCny;
  private String status;
  private String failureReason;
  private String source;
  private Long goldenQuestionId;
  private String tenantId;
  private LocalDateTime createdAt;
}
