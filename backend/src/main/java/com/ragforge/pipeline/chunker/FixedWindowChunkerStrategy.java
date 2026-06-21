package com.ragforge.pipeline.chunker;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FixedWindowChunkerStrategy implements ChunkerStrategy {

  private final TextChunker textChunker;

  @Override
  public String name() {
    return "FIXED_WINDOW";
  }

  @Override
  public boolean supports(DocumentMeta meta) {
    return true;
  }

  @Override
  public List<Chunk> split(CleanedText text, ChunkParams params) {
    ChunkParams p = params == null ? new ChunkParams() : params;
    List<Chunk> chunks =
        textChunker.chunk(value(text), p.getChunkSize(), p.getOverlap());
    chunks.forEach(c -> c.setChunkParamsJson(paramsJson(p)));
    return chunks;
  }

  private static String value(CleanedText text) {
    return text == null || text.getText() == null ? "" : text.getText();
  }

  static Map<String, Object> paramsJson(ChunkParams p) {
    return Map.of(
        "chunkSize", p.getChunkSize(),
        "overlap", p.getOverlap());
  }
}
