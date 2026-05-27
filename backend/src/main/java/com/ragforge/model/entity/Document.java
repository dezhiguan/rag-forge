package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("documents")
public class Document {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long kbId;
  private String filename;
  private String filePath;
  private Long fileSize;
  private String fileType;
  private String parseStatus;
  private Integer chunkCount;
  private String errorMsg;
  private LocalDateTime createdAt;
}
