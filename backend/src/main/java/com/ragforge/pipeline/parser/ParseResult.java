package com.ragforge.pipeline.parser;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParseResult {

  /** Extracted plain text. */
  private String text;

  /** Parse duration in milliseconds. */
  private long parseTimeMs;

  /** Page count for PDF; other formats return 1. */
  private int pageCount;
}
