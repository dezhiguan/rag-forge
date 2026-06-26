package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pgvector.PGvector;
import com.ragforge.mybatis.handler.JsonbStringTypeHandler;
import com.ragforge.mybatis.handler.PGvectorTypeHandler;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName(value = "document_chunks", autoResultMap = true)
public class DocumentChunk {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long docId;
  private Long kbId;
  private Integer chunkIndex;
  private String content;

  @TableField(value = "vl_vector", typeHandler = PGvectorTypeHandler.class)
  private PGvector vlVector;

  @TableField("chunk_type")
  private String chunkType;

  private String chunkerStrategy;

  @TableField(value = "chunker_params_json", typeHandler = JsonbStringTypeHandler.class)
  private String chunkerParamsJson;

  @TableField(value = "chunk_metadata_json", typeHandler = JsonbStringTypeHandler.class)
  private String chunkMetadataJson;

  private String headingPath;
  private String chunkModality;

  // image_key 是 OSS 上原图的 storage key，用于 IMAGE chunk 的详情页预览（presignedGet）。
  // 之前 entity 漏字段，导致即便 worker 把图传上 OSS，落库的 chunk 也拿不回 key、详情页无法显示图。
  //
  // 标 exist=false：MyBatis-Plus 不会把它包进自动生成的 SELECT / INSERT，避免 V25 在云上
  // 没真正应用、image_key 列不存在时整条 SELECT 500。读写都改走 ImagePipelineService 和
  // DocumentServiceImpl 里的原生 JDBC 路径（同时各有 try/catch 兜底）。
  @TableField(exist = false)
  private String imageKey;

  private Integer tokenCount;
  private LocalDateTime createdAt;
}
