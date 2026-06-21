package com.ragforge.pipeline.cleaner;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RawText {
  private String text;
  private String contentType;
  private Integer pageCount;
  private List<String> pageBoundaries;

  public RawText(String text, String contentType, Integer pageCount) {
    this(text, contentType, pageCount, List.of());
  }
}
