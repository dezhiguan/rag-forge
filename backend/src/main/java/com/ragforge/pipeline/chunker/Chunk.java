package com.ragforge.pipeline.chunker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Chunk {

  /** Zero-based chunk index. */
  private int index;

  /** Alias used by V5 chunker strategies. */
  private int seq;

  /** Chunk text content. */
  private String content;

  /** Estimated token count (Chinese: ~1 char per token). */
  private int tokenCount;

  /** Heading path such as "Intro/Chapter 2/2.1 Background". */
  private String headingPath;

  /** Strategy-specific parameters captured when this chunk was generated. */
  private Map<String, Object> chunkParamsJson;

  public Chunk(int index, String content, int tokenCount) {
    this.index = index;
    this.seq = index;
    this.content = content;
    this.tokenCount = tokenCount;
  }

  public void setIndex(int index) {
    this.index = index;
    this.seq = index;
  }

  public void setSeq(int seq) {
    this.seq = seq;
    this.index = seq;
  }
}
