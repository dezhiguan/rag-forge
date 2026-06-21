package com.ragforge.pipeline.chunker;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class ChunkerProfile {

  private String defaultStrategy = "RECURSIVE";
  private List<String> fallbackChain = new ArrayList<>(List.of("RECURSIVE", "FIXED_WINDOW"));
  private ChunkParams params = new ChunkParams();
}
