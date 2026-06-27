package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 管理员敏感访问审计记录（如破玻璃查看全部知识库）。 */
@Data
@TableName("admin_access_audit")
public class AdminAccessAudit {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long adminUserId;
  private String action;
  private String reason;
  private String traceId;
  private LocalDateTime createdAt;
}
