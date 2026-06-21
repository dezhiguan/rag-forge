package com.ragforge.pipeline.chunker;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ChunkingResult {

  private String strategy;
  private ChunkParams params;
  private List<Chunk> chunks;
}
