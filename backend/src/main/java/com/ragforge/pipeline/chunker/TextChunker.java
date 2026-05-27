package com.ragforge.pipeline.chunker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TextChunker {

  public static final int DEFAULT_CHUNK_SIZE = 512;
  public static final int DEFAULT_CHUNK_OVERLAP = 64;

  public List<Chunk> chunk(String text) {
    return chunk(text, DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
  }

  public List<Chunk> chunk(String text, int chunkSize, int chunkOverlap) {
    if (text == null || text.isEmpty()) {
      return Collections.emptyList();
    }
    if (chunkSize <= 0) {
      throw new IllegalArgumentException("chunkSize must be positive");
    }
    if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
      throw new IllegalArgumentException("chunkOverlap must be in [0, chunkSize)");
    }

    int length = text.length();
    if (length <= chunkSize) {
      return List.of(new Chunk(0, text, length));
    }

    int step = chunkSize - chunkOverlap;
    List<Chunk> chunks = new ArrayList<>();
    int start = 0;
    int index = 0;
    while (start < length) {
      int end = Math.min(start + chunkSize, length);
      String content = text.substring(start, end);
      chunks.add(new Chunk(index++, content, content.length()));
      if (end >= length) {
        break;
      }
      start += step;
    }
    return chunks;
  }
}
