package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 组织邀请：按手机号解析；PENDING 接受后才写 org_members。status ∈ PENDING|ACCEPTED|DECLINED|REVOKED|EXPIRED。 */
@Data
@TableName("org_invitations")
public class OrgInvitation {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long orgId;
  private Long inviterUserId;
  private Long inviteeUserId;
  private String inviteePhone;
  private String role;
  private String status;
  private String token;
  private LocalDateTime expiresAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
