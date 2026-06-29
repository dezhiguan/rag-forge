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

  private Long userId;
  /** 检索发生时的当前组织上下文（X-Org-Id），供按组织聚合指标。 */
  private Long orgId;
  private String query;
  private String rewrittenQueries;
  private String strategy;
  private String kbIds;
  private Integer topK;
  private Integer resultCount;
  private Integer latencyMs;

  @TableField(value = "citations_snapshot", typeHandler = JsonbStringTypeHandler.class)
  private String citationsSnapshot;

  /** 该次检索结果的 rerank 分均值；仅精排策略落值，其它为 null。 */
  private Double avgRerankScore;
  /** 检索状态：SUCCESS / ERROR，供成功率聚合。 */
  private String status;

  private LocalDateTime createdAt;
}
