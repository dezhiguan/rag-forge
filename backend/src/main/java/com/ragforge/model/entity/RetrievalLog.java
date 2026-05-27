package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("retrieval_logs")
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
  private LocalDateTime createdAt;
}
