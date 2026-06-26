package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 模型注册表：驱动「模型 & 成本中心」UI 与运行时模型解析。 */
@Data
@TableName("model_config")
public class ModelConfig {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String code;

  @TableField("display_name")
  private String displayName;

  private String vendor;

  /** EMBEDDING/OCR/REWRITE/RERANK/ANSWER/JUDGE */
  private String purpose;

  private String endpoint;

  /** 元/百万 Token */
  @TableField("input_price")
  private BigDecimal inputPrice;

  /** 元/百万 Token */
  @TableField("output_price")
  private BigDecimal outputPrice;

  @TableField("is_local")
  private Boolean isLocal;

  private Boolean enabled;

  @TableField("is_primary")
  private Boolean isPrimary;

  /** 停用时回退到的模型 code */
  @TableField("fallback_code")
  private String fallbackCode;

  @TableField("sort_order")
  private Integer sortOrder;

  @TableField("created_at")
  private LocalDateTime createdAt;

  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
