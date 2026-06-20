package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ragforge.mybatis.handler.JsonbStringTypeHandler;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName(value = "retrieval_logs", autoResultMap = true)
public class RetrievalLog {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String query;
  private String rewrittenQueries;
  private String strategy;
  private String kbIds;
  private Integer topK;
  private Integer resultCount;
  private Integer latencyMs;

  @TableField(value = "citations_snapshot", typeHandler = JsonbStringTypeHandler.class)
  private String citationsSnapshot;

  private LocalDateTime createdAt;
}
