package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ragforge.mybatis.handler.JsonbStringTypeHandler;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName(value = "documents", autoResultMap = true)
public class Document {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long kbId;
  private String filename;
  private String filePath;
  private Long fileSize;
  private String fileType;
  private String fileMd5;
  private String externalId;
  private String sourceUrl;
  private String contentMd5;
  private String storageBucket;
  private String ingestSource;
  private String indexedContent;
  @TableField(value = "clean_report_json", typeHandler = JsonbStringTypeHandler.class)
  private String cleanReportJson;
  private String rechunkStrategy;
  @TableField(value = "rechunk_params_json", typeHandler = JsonbStringTypeHandler.class)
  private String rechunkParamsJson;
  private Long cleanProfileId;
  private Integer version;
  private String parseStatus;
  private Integer chunkCount;
  private String errorMsg;
  private String chunkType;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public String getStorageKey() {
    return filePath;
  }

  public void setStorageKey(String storageKey) {
    this.filePath = storageKey;
  }
}
