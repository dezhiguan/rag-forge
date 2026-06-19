package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("kb_acl")
public class KbAcl {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long kbId;
  private String principalType;
  private String principalId;
  private String permission;
  private LocalDateTime expiresAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
