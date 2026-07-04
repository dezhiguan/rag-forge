package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 每组织 LLM-as-Judge 月度评测预算；org_id 为主键，未配置的组织回退平台默认预算。 */
@Data
@TableName("org_judge_budget")
public class OrgJudgeBudget {

  @TableId(type = IdType.INPUT)
  private Long orgId;

  private BigDecimal monthlyBudgetCny;
  private LocalDateTime updatedAt;
}
