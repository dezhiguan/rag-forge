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

  @TableField(value = "content_vector", typeHandler = PGvectorTypeHandler.class)
  private PGvector contentVector;

  @TableField(value = "image_vector", typeHandler = PGvectorTypeHandler.class)
  private PGvector imageVector;

  @TableField("chunk_type")
  private String chunkType;

  private String chunkerStrategy;

  @TableField(value = "chunker_params_json", typeHandler = JsonbStringTypeHandler.class)
  private String chunkerParamsJson;

  private String headingPath;
  private String chunkModality;
  private String imageKey;

  private Integer tokenCount;
  private LocalDateTime createdAt;
}
