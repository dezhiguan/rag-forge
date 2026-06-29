package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 站内通知：user_id 为网关 auth_user_id；read_at 为空表示未读。 */
@Data
@TableName("notifications")
public class Notification {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long userId;
  private String type;
  private String title;
  private String body;
  private Long refId;
  private LocalDateTime readAt;
  private LocalDateTime createdAt;
}
