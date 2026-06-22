package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("judge_sampling_config")
public class JudgeSamplingConfig {

  @TableId(type = IdType.AUTO)
  private Long id;
  private String scopeType;
  private Long scopeId;
  private String tenantId;
  private BigDecimal sampleRate;
  private Boolean enabled;
  private LocalDateTime updatedAt;
  private String updatedBy;
}
