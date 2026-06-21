package com.ragforge.pipeline.parser;

import java.util.List;
import com.ragforge.pipeline.image.ExtractedImage;
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

  /** Extracted text per page when the parser can preserve page boundaries. */
  private List<String> pageBoundaries;

  /** Images embedded in mixed text documents. */
  private List<ExtractedImage> images;

  public ParseResult(String text, long parseTimeMs, int pageCount) {
    this(text, parseTimeMs, pageCount, List.of(), List.of());
  }

  public ParseResult(String text, long parseTimeMs, int pageCount, List<String> pageBoundaries) {
    this(text, parseTimeMs, pageCount, pageBoundaries, List.of());
  }
}
