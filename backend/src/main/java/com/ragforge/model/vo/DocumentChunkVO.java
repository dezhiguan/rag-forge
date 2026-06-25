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
  // IMAGE chunk 在原文档里的图序号（0,1,2...）。
  // TEXT chunk 里嵌入的 ![image N](rfimg://N) 占位符通过这个 N 反查到对应 IMAGE chunk
  // 拿 imageUrl 做 inline 渲染。
  private Integer figureIndex;
}
