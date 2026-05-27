package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("eval_experiments")
public class EvalExperiment {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long datasetId;
  private String strategy;
  private Boolean enableQueryRewrite;
  private Boolean enableReranker;
  private BigDecimal top3HitRate;
  private BigDecimal top5HitRate;
  private Integer avgLatencyMs;
  private String status;
  private LocalDateTime createdAt;
}
