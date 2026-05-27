package com.ragforge.pipeline.chunker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Chunk {

  /** Zero-based chunk index. */
  private int index;

  /** Chunk text content. */
  private String content;

  /** Estimated token count (Chinese: ~1 char per token). */
  private int tokenCount;
}
