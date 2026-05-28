package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

@Data
@TableName("eval_results")
public class EvalResult {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long experimentId;
  private Long questionId;
  private Boolean hit;
  private Integer hitAt;
  private String recalledChunkIds;
  private String failureReason;
  private BigDecimal score;
  private Integer latencyMs;
}
