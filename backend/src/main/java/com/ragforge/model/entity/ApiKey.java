package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ragforge.mybatis.handler.JsonbStringTypeHandler;
import java.time.LocalDateTime;
import lombok.Data;

@Data
// autoResultMap=true：使字段级 typeHandler 在查询结果映射时生效。
@TableName(value = "api_keys", autoResultMap = true)
public class ApiKey {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String keyName;
  private String apiKey;
  private Boolean enabled;
  private Integer rateLimit;
  private String principalType;
  private String principalId;

  // scopes / allowed_kb_ids 在库中为 jsonb，写入时需转 jsonb，否则 varchar→jsonb 类型不匹配报错（M6-02 500）。
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String scopes;

  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String allowedKbIds;
  /** 所属组织（开发者中心：key 归当前组织）。 */
  private Long orgId;
  /** 可读范围：ORG_ALL(默认) | KB_LIST。 */
  private String scopeMode;
  /** 权限级别：READ(本期仅此) | READ_WRITE(暂不开放)。 */
  private String accessLevel;
  /** 过期时间；null=永不过期。 */
  private LocalDateTime expiresAt;
  /** SHA-256(hex)；批次2 起按 hash 鉴权，明文回退兼容。 */
  private String keyHash;
  /** 展示用前缀，如 sk-rf-xxxxxx。 */
  private String keyPrefix;
  private LocalDateTime lastUsedAt;
  private LocalDateTime createdAt;
}
