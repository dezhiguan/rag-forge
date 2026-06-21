package com.ragforge.pipeline.cleaner;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RemovedRegion {
  private int startOffset;
  private int endOffset;
  private String reason;
  private String text;
}
