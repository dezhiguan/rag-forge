package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ragforge.mybatis.handler.JsonbStringTypeHandler;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName(value = "clean_profiles", autoResultMap = true)
public class CleanProfileEntity {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String scope;
  private Long scopeId;

  @TableField(value = "config", typeHandler = JsonbStringTypeHandler.class)
  private String config;

  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
