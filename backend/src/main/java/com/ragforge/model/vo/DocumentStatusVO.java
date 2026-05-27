package com.ragforge.model.vo;

import lombok.Data;

@Data
public class DocumentStatusVO {

  private String parseStatus;
  private Integer chunkCount;
  private String errorMsg;
}

