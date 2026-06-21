package com.ragforge.pipeline.cleaner;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RawText {
  private String text;
  private String contentType;
  private Integer pageCount;
}
