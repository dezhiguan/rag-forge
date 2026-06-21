package com.ragforge.pipeline.chunker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentMeta {
  private Long docId;
  private Long kbId;
  private String filename;
  private String contentType;
  private long textLength;
}
