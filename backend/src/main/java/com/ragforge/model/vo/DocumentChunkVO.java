package com.ragforge.model.vo;

import lombok.Data;

@Data
public class DocumentChunkVO {

  private Integer chunkIndex;
  private String content;
  private Integer tokenCount;
  private String chunkerStrategy;
  private String headingPath;
  private String chunkModality;
  private String imageKey;
  // IMAGE chunk 的临时预览 URL（presigned GET，TTL 10 分钟）。
  // 前端直接 <img :src="chunk.imageUrl"> 就能看图，不暴露 OSS bucket 给浏览器。
  private String imageUrl;
}
