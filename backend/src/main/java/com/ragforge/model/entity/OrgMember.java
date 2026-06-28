package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 组织成员：user_id 为网关 auth_user_id；role ∈ OWNER|ADMIN|MEMBER。 */
@Data
@TableName("org_members")
public class OrgMember {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long orgId;
  private Long userId;
  private String role;
  private LocalDateTime createdAt;
}
