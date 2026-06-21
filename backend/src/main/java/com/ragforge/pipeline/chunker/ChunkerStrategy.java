package com.ragforge.pipeline.chunker;

import java.util.List;

public interface ChunkerStrategy {
  String name();

  boolean supports(DocumentMeta meta);

  List<Chunk> split(CleanedText text, ChunkParams params);
}
