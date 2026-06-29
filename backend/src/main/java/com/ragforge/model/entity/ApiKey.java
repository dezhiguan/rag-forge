package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("api_keys")
public class ApiKey {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String keyName;
  private String apiKey;
  private Boolean enabled;
  private Integer rateLimit;
  private String principalType;
  private String principalId;
  private String scopes;
  private String allowedKbIds;
  /** 所属组织（开发者中心：key 归当前组织）。 */
  private Long orgId;
  private LocalDateTime lastUsedAt;
  private LocalDateTime createdAt;
}
