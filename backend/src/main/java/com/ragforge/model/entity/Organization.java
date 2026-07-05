package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 组织：拥有成员的 owner 空间（GitHub Org 同构）。知识库可归属组织。 */
@Data
@TableName("organizations")
public class Organization {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String slug;
  private String name;
  /** 组织类型：INDIVIDUAL（个人组织，单人）/ TEAM（团队组织）/ SYSTEM（系统组织，org_id=0，平台团队工作区）。 */
  private String type;
  private Long createdByUserId;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
