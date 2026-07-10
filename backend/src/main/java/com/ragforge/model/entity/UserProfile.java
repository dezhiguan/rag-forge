package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** RAG 本地用户资料（展示用）。凭证落网关，不在此存。 */
@Data
@TableName("user_profile")
public class UserProfile {

  @TableId(value = "auth_user_id", type = IdType.INPUT)
  private Long authUserId;

  private String displayName;
  private String avatar;
  private String bio;
  private String username;
  private String email;
  private String maskedPhone;

  private Boolean onboardingCompleted;

  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
