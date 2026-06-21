package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ragforge.mybatis.handler.JsonbStringTypeHandler;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName(value = "answer_logs", autoResultMap = true)
public class AnswerLog {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String tenantId;
  private String principalId;

  @TableField(exist = false)
  private String kbIdsCsv;

  private String query;
  private String answer;

  @TableField(value = "citations_snapshot", typeHandler = JsonbStringTypeHandler.class)
  private String citationsSnapshot;

  private String retrievalStrategy;
  private String answerMode;
  private String llmModel;
  private Integer promptTokens;
  private Integer completionTokens;
  private Integer retrievalLatencyMs;
  private Integer llmLatencyMs;
  private Integer totalLatencyMs;
  private String traceId;
  private String guardRailResult;
  private LocalDateTime createdAt;
}
