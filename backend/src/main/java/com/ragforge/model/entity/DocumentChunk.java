package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pgvector.PGvector;
import com.ragforge.mybatis.handler.PGvectorTypeHandler;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("document_chunks")
public class DocumentChunk {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long docId;
  private Long kbId;
  private Integer chunkIndex;
  private String content;

  @TableField(value = "content_vector", typeHandler = PGvectorTypeHandler.class)
  private PGvector contentVector;

  @TableField("chunk_type")
  private String chunkType;

  private Integer tokenCount;
  private LocalDateTime createdAt;
}
